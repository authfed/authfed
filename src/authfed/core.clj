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

(defonce state (atom {}))

(defn logout-page
 [request]
 (-> (ring-resp/redirect "/login")
  (assoc :session {})))

(defonce pending (atom {}))

(defn login-page
  [request]
  (let [post-request? (= :post (:request-method request))
        session-id (-> request :cookies (get "ring-session") :value)
        {:keys [code] :as params} (-> request :form-params)
        formkey    (first (filter #{:email :mobile} (keys params)))
        formval    (get params formkey)]
   (cond

    (and (contains? (:session request) :email)
         (contains? (:session request) :mobile))
    (let [users (into {} (map (juxt #(hash-map :email (:email %) :mobile (:mobile %)) identity) config/users))]
     (if-let [user (get users (select-keys (:session request) [:email :mobile]))]
      (-> (ring-resp/redirect "/apps")
          (update :flash assoc :info "Welcome!"))
      (-> (ring-resp/redirect "/login")
          (assoc :session {})
          (update :flash assoc :error "User not found. Access denied."))))

    (and post-request? code)
    (let [six-digits (try (new Integer code) (catch NumberFormatException _ nil))
          {::keys [validator k v]} (get @pending session-id)]
     (if (validator six-digits)
      (do
        (swap! state update session-id assoc k v)
        (ring-resp/redirect "/login"))
      (-> (ring-resp/redirect "/login")
       (update :flash assoc :error "Incorrect code."))))

    (and post-request? formkey)
    (let [secret (ot/generate-secret-key)
          n (ot/get-totp-token secret)]
     (swap! pending assoc session-id {::validator #(ot/is-valid-totp-token? % secret)
                                      ::k formkey ::v formval})
     (email/send-message! {:message {:body {:text (str "Code is " n)}}})
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
                                        (if-not (-> request :session :email)
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
 (ring-resp/redirect "/login"))

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
  (middlewares/session {:store (memory/memory-store state)})
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
