(ns authfed.core
  (:gen-class) ; for -main method in uberjar
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
            [authfed.template :as template]
            [authfed.email :as email]
            [authfed.sms :as sms]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.pprint :refer [pprint]]
            [ring.middleware.resource :as resource]
            [ring.middleware.session.memory :as memory]
            [ring.util.codec :as codec]
            [ring.util.response :as ring-resp]))

(def in-the-future?
 #(if (instance? Instant %)
   (.before (new Date) (Date/from %))
   (.before (new Date) %)))

(def parse-int
 #(try (new Integer %) (catch NumberFormatException _ nil)))

(defn make-sms-challenge [{::keys [session k v]}]
 (let [id (str (java.util.UUID/randomUUID))
       secret (ot/generate-secret-key)
       code (delay (ot/get-totp-token secret))]
  {::id id
   ::session session
   ::k k
   ::v v
   ::secret secret
   ::send! #(sms/send-message! {:to % :message (str "Code is " @code)})
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
                                  :message {:subject "Magic link for login"
                                            :body {:text (str "https://localhost:8443/challenge/" id "?token=" token)}}})
   ::validator #(and (= token %) (in-the-future? expiry))}))

(defonce sessions (atom {}))
(defonce challenges (atom #{}))

(defn start-page
  [request]
  (let [post-request? (= :post (:request-method request))
        session-id (-> request :cookies (get "ring-session") :value)
        {:keys [email mobile]} (-> request :form-params)]
   (assert session-id)
   (cond
    post-request?
    (do
     (let [ch1 (make-email-challenge {::session session-id ::k :email ::v email})
           ch2 (make-sms-challenge {::session session-id ::k :mobile ::v mobile})]
      ((::send! ch1) email)
      ((::send! ch2) mobile)
      (swap! challenges conj ch1 ch2)
      (ring-resp/redirect "/next-challenge")))

    true
    (-> [{:tag "form"
          :attrs {:method "POST" :action "/start"}
          :content [(template/input {:id "__anti-forgery-token"
                                     :type "hidden"
                                     :value (csrf/anti-forgery-token request)})
                    (template/input {:id "email"
                                     :type "text"
                                     :autofocus true
                                     :label "Email"})
                    (template/input {:id "mobile"
                                     :type "text"
                                     :label "Mobile"})
                    (template/input {:id "submit"
                                     :type "submit"
                                     :classes ["btn" "btn-primary"]
                                     :value "Start sign-in process"})]}]
     (ring-resp/response)
     (update :body (partial template/html request))
     (update :body xml/emit-str)))))

(defn next-challenge-page
 [request]
 (let [session-id (-> request :cookies (get "ring-session") :value)]
  (if-let [challenge (->> @challenges (filter #(= (::session %) session-id)) first)]
   (ring-resp/redirect (str "/challenge/" (::id challenge)))
   (-> (ring-resp/redirect "/apps")
       (update :flash assoc :info "Welcome! You are now logged in.")))))

(def label {:email "Email" :mobile "Mobile"})

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

    (= k :email)
    (if-let [token (-> request :query-params :token)]
     (-> [(p "Would you link to approve the request to sign-in?")
          {:tag "form"
           :attrs {:method "POST"}
           :content [(template/input {:id "__anti-forgery-token"
                                      :type "hidden"
                                      :value (csrf/anti-forgery-token request)})
                     (template/input {:id (name k)
                                      :type "text"
                                      :value v
                                      :disabled true
                                      :label (label k)})
                     (template/input {:id "token"
                                      :type "hidden"
                                      :value token})
                     (template/input {:id "submit"
                                      :type "submit"
                                      :classes ["btn" "btn-primary"]
                                      :value "Yes, approve sign-in"})]}]
      (ring-resp/response)
      (update :body (partial template/html request))
      (update :body xml/emit-str))
     (-> [(p "An email has been sent to " (i v) ".")
          (p "Please click the link in that email to continue to the next challenge.")
          {:tag "form"
           :attrs {:method "POST"}
           :content [(template/input {:id "__anti-forgery-token"
                                      :type "hidden"
                                      :value (csrf/anti-forgery-token request)})
                     (template/input {:id "submit"
                                      :type "submit"
                                      :classes ["btn" "btn-primary"]
                                      :value "Ok, done"})]}]
      (ring-resp/response)
      (update :body (partial template/html request))
      (update :body xml/emit-str)))

    (= k :mobile)
    (-> [(p "A six-digit code has been sent to " (i v) ".")
         (p "Please type that code the box below to continue the sign-in process.")
         {:tag "form"
          :attrs {:method "POST"}
          :content [(template/input {:id "__anti-forgery-token"
                                     :type "hidden"
                                     :value (csrf/anti-forgery-token request)})
                    (template/input {:id "token"
                                     :type "text"
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

(defn challenges-page
 [request]
 (let [session-id (-> request :cookies (get "ring-session") :value)]
  (-> (into []
       (for [{::keys [id k v token secret]} (->> @challenges (filter #(= (::session %) session-id)))]
        [{:tag "hr" :content [""]}
         {:tag "a" :attrs {:href (str "/challenge/" id)} :content [(str "Challenge for " (label k))]}
         {:tag "p" :content [(or token (ot/get-totp-token secret))]}
         (template/input {:id (name k)
                          :type "text"
                          :value v
                          :disabled true
                          :label (label k)})
         {:tag "form"
          :attrs {:method "POST" :action (str "/challenge/" id)}
          :content [(template/input {:id "__anti-forgery-token"
                                     :type "hidden"
                                     :value (csrf/anti-forgery-token request)})
                    (template/input {:id "token"
                                     :type "text"
                                     :label "Token"})
                    (template/input {:id "submit"
                                     :type "submit"
                                     :classes ["btn" "btn-primary"]
                                     :value "Confirm challenge"})]}]))
   (ring-resp/response)
   (update :body (partial template/html request))
   (update :body xml/emit-str))))

(defonce pending (atom {}))

(defn login-page
  [request]
  (let [post-request? (= :post (:request-method request))
        session (:session request)
        session-id (-> request :cookies (get "ring-session") :value)
        {:keys [code] :as params} (-> request :form-params)
        formkey    (first (filter #{:email :mobile} (keys params)))
        formval    (get params formkey)]
   (cond

    (and (contains? session :email)
         (contains? session :mobile))
    (let [users (into {} (map (juxt #(hash-map :email (:email %) :mobile (:mobile %)) identity) config/users))]
     (if-let [user (get users (select-keys session [:email :mobile]))]
      (-> (ring-resp/redirect "/apps")
          (update :flash assoc :info "Welcome!"))
      (-> (ring-resp/redirect "/login")
          (assoc :session {})
          (update :flash assoc :error "User not found. Access denied."))))

    (and post-request? code)
    (let [six-digits (try (new Integer code) (catch NumberFormatException _ nil))
          {::keys [validator k v]} (get @pending session-id)]
     (if (validator six-digits)
      (-> (ring-resp/redirect "/login")
       (assoc :session (assoc session k v))
       (update :flash assoc :info "Correct! Now your mobile number as well please."))
      (-> (ring-resp/redirect "/login")
       (update :flash assoc :error "Incorrect code."))))

    (and post-request? formkey)
    (let [secret (ot/generate-secret-key)
          n (ot/get-totp-token secret)]
     (swap! pending assoc session-id {::validator #(ot/is-valid-totp-token? % secret)
                                      ::k formkey ::v formval})
     (case formkey
      :email (email/send-message! {:message {:subject "Six-digit code for login"
                                             :body {:text (str "Code is " n)}}
                                   :destination {:to-addresses [formval]}})
      :mobile (sms/send-message! {:message (str "Code is " n) :to formval}))
     (-> (ring-resp/response [{:tag "form"
                               :attrs {:method "POST" :action "/login"}
                               :content [(template/input {:id "__anti-forgery-token"
                                                          :type "hidden"
                                                          :value (csrf/anti-forgery-token request)})
                                         (template/input {:id (name formkey)
                                                          :type "text"
                                                          :disabled true
                                                          :value formval
                                                          :label ({:email "Email" :mobile "Mobile"} formkey)})
                                         (template/input {:id "code"
                                                          :type "text"
                                                          :autofocus true
                                                          :label "Code"})
                                         (template/input {:id "submit"
                                                          :type "submit"
                                                          :classes ["btn" "btn-primary"]
                                                          :value "Try six-digit code"})]}])
      (update :body (partial template/html request))
      (update :body xml/emit-str)))

    true ;; else
    (-> (ring-resp/response [{:tag "form"
                              :attrs {:method "POST" :action "/login"}
                              :content [(template/input {:id "__anti-forgery-token"
                                                         :type "hidden"
                                                         :value (csrf/anti-forgery-token request)})
                                        (if-not (contains? session :email)
                                         (template/input {:id "email"
                                                          :type "text"
                                                          :autofocus true
                                                          :label "Email"})
                                         (template/input {:id "mobile"
                                                          :type "text"
                                                          :autofocus true
                                                          :label "Mobile"}))
                                        (template/input {:id "submit"
                                                         :type "submit"
                                                         :classes ["btn" "btn-primary"]
                                                         :value "Sign in"})]}])
     (update :body (partial template/html request))
     (update :body xml/emit-str)))))

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
       email (-> request :session :email)]
  (assert (and app-id email))
  (-> ((get saml-apps app-id) email)
   (update :body (partial template/html request))
   (update :body xml/emit-str))))

(defn apps-page
 [request]
 (let [email (-> request :session :email)]
  (-> (ring-resp/response
       [{:tag "ul"
         :content (for [k (keys saml-apps)]
                   {:tag "li"
                    :content [{:tag "a" :attrs {:href (str "/apps/" k)}
                               :content [(::saml/name (meta (saml-apps k)))]}]})}])
   (update :body (partial template/html request))
   (update :body xml/emit-str))))

(defn home-page
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

(def common-interceptors
 ^:interceptors
 [(body-params/body-params)
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

(def error-message
 {:tag "span"
  :content [{:tag "strong" :content ["Error: "]}
            "please log in."]})

(defn check [ks]
 {:name (keyword (gensym "require-"))
  :enter (fn [ctx]
          (let [session (:session (:request ctx))]
           (if (every? session ks)
            ctx
            (-> ctx
             (assoc-in [:response] (ring-resp/redirect "/login"))
             (assoc-in [:response :flash :error] error-message)))))})

(def routes
 (route/expand-routes
  [[:catch-all ["/" {:get `apex-redirects}]]
   [:net-authfed :https (::config/hostname config/params)
    ["/" common-interceptors {:get `home-page}]
    ["/start" common-interceptors {:any `start-page}]
    ["/challenges" common-interceptors {:any `challenges-page}]
    ["/challenge/:id" common-interceptors {:any `challenge-page}]
    ["/next-challenge" common-interceptors {:any `next-challenge-page}]
    ["/login" common-interceptors {:any `login-page}]
    ["/logout" common-interceptors {:any `logout-page}]
    ["/apps" common-interceptors {:get `apps-page}]
    ["/apps/:app-id" common-interceptors {:get `app-page}]]]))

(def keystore-password (apply str less.awful.ssl/key-store-password))
(def keystore-instance
 (let [ksi (java.security.KeyStore/getInstance (java.security.KeyStore/getDefaultType))]
  (.load ksi nil nil)
  (doall
   (for [x (::config/letsencrypt config/params)]
    (.setKeyEntry ksi x (less.awful.ssl/private-key (str x "-privkey.pem"))
                        less.awful.ssl/key-store-password
                        (less.awful.ssl/load-certificate-chain (str x "-fullchain.pem")))))
  ksi))

(def ssl-context-factory
  (doto (new SslContextFactory)
   (.setKeyStore keystore-instance)
   (.setKeyStorePassword keystore-password)
   (.setCipherComparator HTTP2Cipher/COMPARATOR)
   (.setUseCipherSuitesOrder true)))

(def service
  {:env :prod
   ::http/routes routes
   ::http/resource-path "/public"
   ::http/type :jetty
   ::http/host "0.0.0.0"
   ::http/port (::config/http-port config/params)
   ::http/container-options {:h2c? true
                             :h2? false
                             :ssl-context-factory ssl-context-factory
                             :ssl-port (::config/ssl-port config/params)
                             :ssl? true}})

(defonce runnable
  (-> service
    (assoc ::http/routes #(deref #'routes))
    (assoc ::http/join? false)
    http/create-server))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (println "\nCreating your server... see logs in /var/log/authfed/")
  (http/start runnable))
