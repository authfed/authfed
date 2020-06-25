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

(def label {::email "Email" ::mobile "Mobile"})

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
                                  :message {:subject "Confirmation token for sign-in"
                                            :body {:text token :html token}}})
   ::validator #(and (= token %) (in-the-future? expiry))}))

(defonce sessions (atom {}))
(defonce challenges (atom #{}))

(defn start-page
  [request]
  (let [post-request? (= :post (:request-method request))
        session-id (-> request :cookies (get "ring-session") :value)
        {:keys [email mobile]} (-> request :form-params)
        [user & _] (filter #(and (= (:email %) email) (= (:mobile %) mobile)) config/users)
        _ (assert session-id)]
   (if post-request?
    (if (nil? user)
     (-> (ring-resp/redirect "/start")
       (update :flash assoc :error "User not found."))
     (do
      (let [ch1 (make-email-challenge {::session session-id ::k ::email ::v email})
            ch2 (make-sms-challenge {::session session-id ::k ::mobile ::v mobile})]
       (swap! challenges conj ch1 ch2)
       (ring-resp/redirect "/next-challenge"))))
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
 (let [post-request? (= :post (:request-method request))
       session-id (-> request :cookies (get "ring-session") :value)
       {::keys [id k v send!] :as challenge}
       (->> @challenges (filter #(= (::session %) session-id)) (sort-by ::k) first)]
  (cond

   (nil? challenge)
   (-> (ring-resp/redirect "/apps")
       (update :flash assoc :info "Welcome! You are now logged in."))

   post-request?
   (do
    (send! v)
    (ring-resp/redirect (str "/challenge/" id)))

    true
    (-> [{:tag "form"
          :attrs {:method "POST"}
          :content [(template/input {:id "__anti-forgery-token"
                                     :type "hidden"
                                     :value (csrf/anti-forgery-token request)})
                    (template/input {:id (name k)
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
         (p "Please type that code the box below to complete the sign-in process.")
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
          (let [session (-> ctx :request :session)]
           (if (every? session ks)
            ctx
            (-> ctx
             (assoc-in [:response] (ring-resp/redirect "/start"))
             (assoc-in [:response :flash :error] error-message)))))})

(def routes
 (route/expand-routes
  [[:catch-all ["/" {:get `apex-redirects}]]
   [:net-authfed :https (::config/hostname config/params)
    ["/" common-interceptors {:get `home-page}]
    ["/start" common-interceptors {:any `start-page}]
    ["/challenge/:id" common-interceptors {:any `challenge-page}]
    ["/next-challenge" common-interceptors {:any `next-challenge-page}]
    ["/login" common-interceptors {:any `login-page}]
    ["/logout" common-interceptors {:any `logout-page}]
    ["/apps" (conj common-interceptors (check [::email ::mobile])) {:get `apps-page}]
    ["/apps/:app-id" (conj common-interceptors (check [::email ::mobile])) {:get `app-page}]]]))

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
