(ns authfed.core
  (:import [org.eclipse.jetty.util.ssl SslContextFactory]
           [java.net URLEncoder]
           [java.util Date]
           [java.time Instant]
           [org.eclipse.jetty.http2 HTTP2Cipher]
           [java.io File FileNotFoundException])
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.http.route :as route]
            [less.awful.ssl]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [one-time.core :as ot]
            [authfed.saml :as saml]
            [authfed.config :as config]
            [authfed.util :as util]
            [authfed.template :as template]
            [authfed.email :as email]
            [authfed.sms :as sms]
            [clojure.data.xml :as xml]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.pprint :refer [pprint]]
            [ring.middleware.resource :as resource]
            [ring.middleware.session.memory :as memory]
            [ring.util.codec :as codec]
            [ring.util.response :as ring-resp]))

(def label {::email "Email" ::mobile "Mobile"})

(defn logged-in? [session]
 (every? session [::email ::mobile]))

(def in-the-future?
 #(if (instance? Instant %)
   (.before (new Date) (Date/from %))
   (.before (new Date) %)))

(def parse-int
 #(try (new Integer %) (catch NumberFormatException _ nil)))

(defn make-sms-challenge [{::keys [session k v]}]
 (let [id (str (java.util.UUID/randomUUID))
       secret (ot/generate-secret-key)]
  {::id id
   ::session session
   ::k k
   ::v v
   ::secret secret
   ::send! #(sms/send-message! {:to % :message (str "Code is " (ot/get-totp-token secret))})
   ::validator #(when-let [n (parse-int %)]
                 (or (ot/is-valid-totp-token? n secret)
                     (ot/is-valid-totp-token? n secret {:date (Date/from (.plusSeconds (Instant/now) 30))})))}))

(defn make-email-challenge [{::keys [session k v]}]
 (let [id (str (java.util.UUID/randomUUID))
       token (str (java.util.UUID/randomUUID))
       expiry (.plusSeconds (Instant/now) 3600)]
  {::id id
   ::session session
   ::k k
   ::v v
   ::token token
   ::send! #(email/send-message! {:destination {:to-addresses [%]}
                                  :message {:subject "Confirmation token for login"
                                            :body {:text token :html token}}})
   ::validator #(and (= token %) (in-the-future? expiry))}))

(defonce challenges (atom #{}))

(defonce sessions
 (atom
  (try
   (edn/read-string (slurp "sessions.edn"))
   (catch Exception _ {}))))

(add-watch sessions "save-to-disk"
 (fn [k r o n]
  (spit "sessions.edn" n)))

(defn start-page
  [request]
  (let [post-request? (= :post (:request-method request))
        session-id (-> request :cookies (get "ring-session") :value)
        username (-> request :form-params :username)
        user (get config/users username)]
   (if post-request?
    (if (nil? user)
     (-> (ring-resp/redirect "/start")
       (update :flash assoc :error "User not found."))
     (-> (ring-resp/redirect "/next-challenge")
       (update :session assoc ::username username)))
    (-> [{:tag "form"
          :attrs {:method "POST" :action "/start"}
          :content [(template/input {:id "__anti-forgery-token"
                                     :type "hidden"
                                     :value (csrf/anti-forgery-token request)})
                    (template/input {:id "username"
                                     :type "text"
                                     :autofocus true
                                     :label "Username"})
                    (template/input {:id "submit"
                                     :type "submit"
                                     :classes ["btn" "btn-primary"]
                                     :value "Log in"})]}]
     (ring-resp/response)
     (update :body (partial template/html request))
     (update :body xml/emit-str)))))

(defn next-challenge-page
 [request]
 (let [post-request? (= :post (:request-method request))
       session (-> request :session)
       session-id (-> request :cookies (get "ring-session") :value)
       {::keys [id k v send!] :as challenge}
       (->> @challenges (filter #(= (::session %) session-id)) (sort-by ::k) first)]
  (cond

   (nil? session-id)
   (-> (ring-resp/redirect "/start")
       (update :flash assoc :error "Something went wrong (missing session cookie)."))

   post-request?
   (do
    (assert challenge)
    (send! v)
    (ring-resp/redirect (str "/challenge/" id)))

   (logged-in? session)
   (-> (ring-resp/redirect "/apps")
       (update :flash assoc :info "Welcome! You are now logged in."))

   (and (nil? challenge) (-> session ::email nil?))
   (if-let [email (-> session ::username config/users :email)]
    (let [ch (make-email-challenge {::session session-id ::k ::email ::v email})]
     (swap! challenges conj ch)
     (ring-resp/redirect "/next-challenge"))
    (-> (ring-resp/redirect "/start")
        (update :flash assoc :error "Could not find email address.")))

   (and (nil? challenge) (-> session ::mobile nil?))
   (if-let [mobile (-> session ::username config/users :mobile)]
    (let [ch (make-sms-challenge {::session session-id ::k ::mobile ::v mobile})]
     (swap! challenges conj ch)
     (ring-resp/redirect "/next-challenge"))
    (-> (ring-resp/redirect "/start")
        (update :flash assoc :error "Could not find mobile number.")))

    true
    (-> [{:tag "form"
          :attrs {:method "POST"}
          :content [(template/input {:id "__anti-forgery-token"
                                     :type "hidden"
                                     :value (csrf/anti-forgery-token request)})
                    (template/input {:id "__unused"
                                     :type "text"
                                     :disabled true
                                     :value v
                                     :label (label k)})
                    (template/input {:id "submit"
                                     :type "submit"
                                     :classes ["btn" "btn-secondary"]
                                     :autofocus true
                                     :value "Send confirmation"})]}]
     (ring-resp/response)
     (update :body (partial template/html request))
     (update :body xml/emit-str)))))

(defn p [& xs] {:tag "p" :content xs})
(defn strong [& xs] {:tag "strong" :content xs})
(defn i [& xs] {:tag "i" :content xs})

(defn challenge-page
  [request]
  (let [post-request? (= :post (:request-method request))
        challenge-id (-> request :path-params :id)
        challenge (->> @challenges (filter #(= (::id %) challenge-id)) first)
        {::keys [validator session k v]} challenge
        token (-> request :form-params :token)]
   (assert challenge-id)
   (cond

    (nil? challenge)
    (ring-resp/not-found "not found\n")

    post-request?
    (if ((::validator challenge) token)
     (do
      (swap! challenges disj challenge)
      (swap! sessions update session assoc k v)
      (ring-resp/redirect "/next-challenge"))
     (-> ["empty payload"]
         (ring-resp/response)
         (update :body (partial template/html (update request :flash assoc :error "Problem with token.")))
         (update :body xml/emit-str)))

    (= k ::email)
    (-> [(p "An email has been sent to " (i v) ".")
         (p "Please copy and paste the confirmation token from that email to continue to the next challenge.")
         {:tag "form"
          :attrs {:method "POST"}
          :content [(template/input {:id "__anti-forgery-token"
                                     :type "hidden"
                                     :value (csrf/anti-forgery-token request)})
                    (template/input {:id "token"
                                     :type "text"
                                     :autofocus true
                                     :label "Token"})
                    (template/input {:id "submit"
                                     :type "submit"
                                     :classes ["btn" "btn-primary"]
                                     :value "Ok, continue"})]}]
     (ring-resp/response)
     (update :body (partial template/html request))
     (update :body xml/emit-str))

    (= k ::mobile)
    (-> [(p "A six-digit code has been sent to " (i v) ".")
         (p "Please type that code the box below to complete the login process.")
         {:tag "form"
          :attrs {:method "POST"}
          :content [(template/input {:id "__anti-forgery-token"
                                     :type "hidden"
                                     :value (csrf/anti-forgery-token request)})
                    (template/input {:id "token"
                                     :type "text"
                                     :autofocus true
                                     :label "Code"})
                    (template/input {:id "submit"
                                     :type "submit"
                                     :classes ["btn" "btn-primary"]
                                     :value "Confirm code"})]}]
     (ring-resp/response)
     (update :body (partial template/html request))
     (update :body xml/emit-str))

    true
    (ring-resp/response "hello world\n"))))

(defn make-saml-handler [config]
 (with-meta
  (fn [email]
   (ring-resp/response [{:tag "form"
                         :attrs {:method "POST" :action "https://signin.aws.amazon.com/saml"}
                         :content [(template/input {:id "SAMLResponse"
                                                    :type "hidden"
                                                    :value (-> email
                                                              (saml/saml-response config)
                                                              (saml/sign-and-serialize config)
                                                              .getBytes
                                                              codec/base64-encode)})
                                   (template/input {:id "submit"
                                                    :type "submit"
                                                    :classes ["btn" "btn-primary"]
                                                    :value "Sign in to AWS"})]}]))
  config))

(def saml-apps
 (into {} (map (juxt ::saml/id make-saml-handler) config/saml)))

(defn app-page
 [request]
 (let [app-id (-> request :path-params :app-id)
       email (-> request :session ::email)]
  (assert (and app-id email))
  (-> ((get saml-apps app-id) email)
   (update :body (partial template/html request))
   (update :body xml/emit-str))))

(defn apps-page
 [request]
 (let [email (-> request :session ::email)
       apps (filter (constantly true) saml-apps)]
  (-> [{:tag "ul"
        :content (for [[k v] apps]
                  {:tag "li"
                   :content [{:tag "a" :attrs {:href (str "/apps/" k)}
                              :content [(-> v meta ::saml/name)]}]})}]
   (ring-resp/response)
   (update :body (partial template/html request))
   (update :body xml/emit-str))))

(defn home-page
 [request]
 (ring-resp/redirect "/start"))

(defn login-page
 [request]
 (ring-resp/redirect "/start"))

(defn logout-page
 [request]
 (-> (ring-resp/redirect "/start")
  (assoc :session {})))

; (def auth-flow
;  {:name ::auth-flow
;   :enter (fn [ctx]
;           (condp set/subset? (set (keys (:session (:request ctx))))
;            #{:email :mobile}
;            (if (= "/app" (-> ctx :request :path-info)) ctx
;             (assoc ctx :response (ring-resp/redirect "/apps")))
;            #{:email}
;            (if (= "/pending" (-> ctx :request :path-info)) ctx
;             (assoc ctx :response (ring-resp/redirect "/pending")))
;            #{}
;            (if (= "/login" (-> ctx :request :path-info)) ctx
;             (-> ctx
;              (assoc-in [:response] (ring-resp/redirect "/login"))
;              (assoc-in [:response :flash :error] error-message)))))})

(def extra-long-sessions
{:name (keyword (gensym "extra-long-sessions-"))
 :leave (fn [ctx]
         (let [kseq [:response :headers "Set-Cookie"]
               one-month (* 60 60 24 30)]
          (if-let [[c & _] (get-in ctx kseq)]
           (assoc-in ctx kseq (str c ";Max-Age=" one-month))
           ctx)))})

(def common-interceptors
 ^:interceptors
 [(body-params/body-params)
  extra-long-sessions
  (middlewares/session {:store (memory/memory-store sessions)})
  (middlewares/flash)
  (csrf/anti-forgery)
  http/html-body
  ; auth-flow
])

(defn apex-redirects
 [request]
 (if-let [target (get config/targets (or (:server-name request) (get (:headers request) "host")))]
  (ring-resp/redirect target)
  (ring-resp/not-found "not found")))

(defn remove-prefix [s]
 {:name (keyword (gensym "remove-prefix-"))
  :enter (fn [ctx] (update-in ctx [:request :path-info] #(.substring % (count s))))})

(def check-logged-in
 (let [error-message {:tag "span"
                      :content [{:tag "strong" :content ["Error: "]}
                                "please log in."]}]
  {:name (keyword (gensym "check-logged-in-"))
   :enter (fn [ctx]
           (let [session (-> ctx :request :session)]
            (if (logged-in? session)
             ctx
             (-> ctx
              (assoc-in [:response] (ring-resp/redirect "/start"))
              (assoc-in [:response :flash :error] error-message)))))}))

(defn config-page [req]
 (let [post-request? (= :post (:request-method req))]
  (when post-request?
   (load-file "src/authfed/config.clj"))
  (-> (into {}
       (map #(vector (.getName %) (util/md5 (slurp (.getPath %))))
        (.listFiles (new java.io.File config/basedir))))
   (json/write-str)
   (ring-resp/response)
   (ring-resp/content-type "application/json"))))
