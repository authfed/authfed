(ns authfed.server
  (:gen-class) ; for -main method in uberjar
  (:import [org.eclipse.jetty.util.ssl SslContextFactory]
           [org.eclipse.jetty.http2 HTTP2Cipher])
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.http.route :as route]
            [less.awful.ssl]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.ring-middlewares :as middlewares]
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
 (-> (ring-resp/redirect "/")
  (update :session dissoc :email)))

(defn login-page
  [request]
  (let [hashes (into {} (map (juxt :email :password) config/users))
        email (-> request :form-params :email)
        password (hashers/check (-> request :form-params :password) (hashes email))]
   (if (and email password (= :post (:request-method request)))
    (-> (ring-resp/redirect "/aws")
        (update :session merge {:email email}))
    (-> (ring-resp/response [{:tag "form"
                              :attrs {:method "POST" :action "/login"}
                              :content [(template/input {:id "__anti-forgery-token"
                                                         :type "hidden"
                                                         :value (csrf/anti-forgery-token request)})
                                        (template/input {:id "email"
                                                         :type "text"
                                                         :label "Email"})
                                        (template/input {:id "password"
                                                         :type "password"
                                                         :label "Password"})
                                        (template/input {:id "submit"
                                                         :type "submit"
                                                         :class ["btn" "btn-primary"]
                                                         :value "Sign in"})]}])
     (update :body (partial template/html request))
     (update :body xml/emit-str)))))

(defn aws-page
  [request]
  (if-let [email (-> request :session :email)]
   (-> (ring-resp/response [{:tag "form"
                             :attrs {:method "POST" :action "https://signin.aws.amazon.com/saml"}
                             :content [(template/input {:id "SAMLResponse"
                                                        :type "hidden"
                                                        :value (-> (saml/saml-response email)
                                                                  saml/sign-and-serialize
                                                                  .getBytes
                                                                  codec/base64-encode)})
                                       (template/input {:id "submit"
                                                        :type "submit"
                                                        :class ["btn" "btn-primary"]
                                                        :value "Sign in to AWS"})]}])
    (update :body (partial template/html request))
    (update :body xml/emit-str))
   (ring-resp/redirect "/login")))

(defn home-page
 [request]
 (-> (ring-resp/response [{:tag "p" :content "Hello World!"}
                          {:tag "br"}
                          {:tag "pre" :content (with-out-str (pprint (:uri request)))}])
  (update :body (partial template/html request))
  (update :body xml/emit-str)))

(def common-interceptors
 [(body-params/body-params)
  (middlewares/session {:store (memory/memory-store)})
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

(def routes
 [[:catch-all ["/" {:get `apex-redirects}]]
  [:net-authfed :https "authfed.net"
   ["/" ^:interceptors `common-interceptors {:get `home-page}]
   ["/vault/*" ^:interceptors `(conj common-interceptors
                                     #(def asdf %)
                                     #(assert (:ssl-client-cert %))
                                     (remove-prefix "/vault")
                                     (middlewares/file-info))
               {:get (middlewares/file (::config/vault config/params))}]
   ["/debug" ^:interceptors `common-interceptors {:any `debug-page}]
   ["/login" ^:interceptors `common-interceptors {:any `login-page}]
   ["/logout" ^:interceptors `common-interceptors {:any `logout-page}]
   ["/aws" ^:interceptors `common-interceptors {:get `aws-page}]
   ["/about" ^:interceptors `common-interceptors {:get `about-page}]]])

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

(def trust-store
  (less.awful.ssl/trust-store (::config/cacert config/params)))

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
   (.setUseCipherSuitesOrder true)
   (.setWantClientAuth true)                   ;; want (don't need) client authentication
   (.setTrustStore trust-store)                ;; if you provide a cert it must be signed
   (.setEndpointIdentificationAlgorithm nil))) ;; but don't care to check hostname or ip

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
    (assoc ::http/routes #(route/expand-routes (deref #'routes)))
    (assoc ::http/join? false)
    http/create-server))

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (println "\nCreating your server... see logs in /var/log/authfed/")
  (http/start runnable))
