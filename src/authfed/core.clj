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
            [one-time.qrgen :as qrgen]
            [authfed.saml :as saml]
            [authfed.config :as config]
            [authfed.template :as template]
            [buddy.hashers :as hashers]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [ring.middleware.resource :as resource]
            [ring.middleware.session.memory :as memory]
            [ring.util.codec :as codec]
            [ring.util.response :as ring-resp]))

(defn about-page
  [request]
  (ring-resp/response (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about-page))))

(defn logout-page
 [request]
 (-> (ring-resp/redirect "/login")
  (update :session dissoc :email)))

(defn login-page
  [request]
  (let [hashes (into {} (map (juxt :email :password) config/users))
        email (-> request :form-params :email)
        password (-> request :form-params :password)
        password-okay? (hashers/check password (hashes email))]
   (if (and email password-okay? (= :post (:request-method request)))
    (-> (ring-resp/redirect "/totp")
        (update :session merge {:email email}))
    (-> (ring-resp/response [{:tag "form"
                              :attrs {:method "POST" :action "/login"}
                              :content [(template/input {:id "__anti-forgery-token"
                                                         :type "hidden"
                                                         :value (csrf/anti-forgery-token request)})
                                        (template/input {:id "email"
                                                         :type "text"
                                                         :autofocus true
                                                         :label "Email"})
                                        (template/input {:id "password"
                                                         :type "password"
                                                         :label "Password"})
                                        (template/input {:id "submit"
                                                         :type "submit"
                                                         :classes ["btn" "btn-primary"]
                                                         :value "Sign in"})]}])
     (update :body (partial template/html (assoc-in request [:flash :error] (when (and email (not password-okay?)) "Username or password incorrect."))))
     (update :body xml/emit-str)))))

(defonce totp-secrets (atom (into {} (map (juxt :email :totp-secret) config/users))))

(def qrcode-uri
 #(->> % qrgen/totp-stream .toByteArray codec/base64-encode (str "data:image/png;base64,")))

(defn otpauth-uri
 [{:keys [user secret label]}]
 (str
  "otpauth://totp/"
  (URLEncoder/encode label)
  ":"
  (URLEncoder/encode user)
  "?secret="
  (URLEncoder/encode secret)
  "&issuer="
  (URLEncoder/encode label)))

(defn totp-page
  [request]
  (let [email (-> request :session :email)
        secret (get @totp-secrets email)
        six-digits (try (-> request :form-params :six-digits Integer.)
                    (catch NumberFormatException _ nil))
        totp-okay? (and six-digits secret
                    (or (ot/is-valid-totp-token? six-digits secret {:date (Date/from (Instant/now))})
                        (ot/is-valid-totp-token? six-digits secret {:date (Date/from (.minusSeconds (Instant/now) 5))})))]
   (cond
    ;; user does not have TOTP set up yet
    (nil? (get @totp-secrets email))
    (let [secret (ot/generate-secret-key)
          params {:user email
                  :secret secret
                  :label (::config/hostname config/params)}]
     (swap! totp-secrets assoc email secret)
     (-> (ring-resp/response [{:tag "img"
                               :attrs {:src (qrcode-uri params)}}
                              {:tag "p" :content [{:tag "a"
                                                   :attrs {:href (otpauth-uri params)}
                                                   :content "Open in soft token app"}]}
                              {:tag "form"
                               :attrs {:method "POST" :action "/totp"}
                               :content [(template/input {:id "__anti-forgery-token"
                                                          :type "hidden"
                                                          :value (csrf/anti-forgery-token request)})
                                         (template/input {:id "submit"
                                                          :type "submit"
                                                          :classes ["btn" "btn-primary"]
                                                          :value "Done"})]}])
      (update :body (partial template/html request))
      (update :body xml/emit-str)))
    ;; user already has TOTP set up, and valid six digits
    (and six-digits totp-okay?)
    (-> (ring-resp/redirect "/apps")
     (update :session merge {:email email :totp? true})
     (update :body (partial template/html request))
     (update :body xml/emit-str))
    ;; user already has TOTP set up, so ask for six digits
    :else
    (-> (ring-resp/response [{:tag "form"
                              :attrs {:method "POST" :action "/totp"}
                              :content [(template/input {:id "__anti-forgery-token"
                                                         :type "hidden"
                                                         :value (csrf/anti-forgery-token request)})
                                        (template/input {:id "six-digits"
                                                         :type "text"
                                                         :autofocus true
                                                         :label "Six digits"})
                                        (template/input {:id "submit"
                                                         :type "submit"
                                                         :classes ["btn" "btn-primary"]
                                                         :value "Sign in"})]}])
     (update :body (partial template/html (assoc-in request [:flash :error] (when (and six-digits (not totp-okay?)) "Six-digit TOTP was incorrect."))))
     (update :body xml/emit-str)))))

(defn make-saml-handler [config]
 (fn [user]
  (ring-resp/response [{:tag "form"
                        :attrs {:method "POST" :action "https://signin.aws.amazon.com/saml"}
                        :content [(template/input {:id "SAMLResponse"
                                                   :type "hidden"
                                                   :value (-> (saml/saml-response user config)
                                                             saml/sign-and-serialize
                                                             .getBytes
                                                             codec/base64-encode)})
                                  (template/input {:id "submit"
                                                   :type "submit"
                                                   :classes ["btn" "btn-primary"]
                                                   :value "Sign in to AWS"})]}])))

(def saml-apps
 (into {} (map (juxt :id #(-> % (dissoc :id) (update :handler make-saml-handler))) config/saml)))

(defn app-page
 [request]
 (let [app-id (:app-id (:path-params request))
       email (-> request :session :email)]
  (assert (and app-id email))
  (-> ((:handler (get saml-apps app-id)) email)
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
                               :content [(:name (saml-apps k))]}]})}])
   (update :body (partial template/html request))
   (update :body xml/emit-str))))

(defn home-page
 [request]
 (ring-resp/redirect "/login"))

(defonce session-store (memory/memory-store))

(def common-interceptors
 ^:interceptors
 [(body-params/body-params)
  (middlewares/session {:store session-store})
  (middlewares/flash)
  (csrf/anti-forgery)
  http/html-body])

(defn debug-page
 [request]
 (do (def asdf request)
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (str (with-out-str (pprint request)) \newline)}))

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
    ["/debug" common-interceptors {:any `debug-page}]
    ["/login" common-interceptors {:any `login-page}]
    ["/totp" (conj common-interceptors (check [:email])) {:any `totp-page}]
    ["/logout" common-interceptors {:any `logout-page}]
    ["/apps" (conj common-interceptors (check [:email :totp?])) common-interceptors {:get `apps-page}]
    ["/apps/:app-id" (conj common-interceptors (check [:email :totp?])) {:get `app-page}]
    ["/about" common-interceptors {:get `about-page}]]]))

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

;; add another cert+key to the key store
; (-> runnable
;  ::http/container-options
;  :ssl-context-factory
;  .getKeyStore
;  (.setKeyEntry "cert2"
;                (less.awful.ssl/private-key "dummy-private2.pem")
;                less.awful.ssl/key-store-password
;                (less.awful.ssl/load-certificate-chain "dummy-public2.pem")))
;; reload the ssl context factory
; (-> runnable
;  ::http/container-options
;  :ssl-context-factory
;  (.reload (reify java.util.function.Consumer (accept [this _] _))))

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
