(ns authfed.server
  (:gen-class) ; for -main method in uberjar
  (:import [org.eclipse.jetty.util.ssl SslContextFactory]
           [org.eclipse.jetty.http2 HTTP2Cipher])
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [less.awful.ssl]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [authfed.saml :as saml]
            [authfed.config :as config]
            [authfed.template :as template]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [ring.middleware.resource :as resource]
            [ring.middleware.session.cookie :as cookie]
            [ring.util.codec :as codec]
            [ring.util.response :as ring-resp]))

(defn about-page
  [request]
  (ring-resp/response (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about-page))))

(defn login-page
  [request]
  (let [samlresponse (saml/sign-and-serialize (saml/saml-response))]
    (ring-resp/response (str "<form method=\"POST\" action=\"https://signin.aws.amazon.com/saml\"><input type=\"hidden\" name=\"SAMLResponse\" value=" (codec/base64-encode (.getBytes samlresponse)) " /><input type=\"submit\" value=\"Submit\" /></form>"))))

(defn home-page
 [request]
 (let [sid (get-in request [:session ::id] (str (java.util.UUID/randomUUID)))]
  (-> (ring-resp/response [{:tag "p" :content "Hello World!"}
                           {:tag "br"}
                           {:tag "pre" :content (with-out-str (pprint (:session request)))}])
   (update :body template/html)
   (update :body xml/emit-str)
   (assoc-in [:session ::id] sid))))

(def common-interceptors
 [(body-params/body-params)
  http/html-body
  (middlewares/session {:store (cookie/cookie-store)})])

(def routes #{["/" :get (conj common-interceptors `home-page)]
              ["/favicon.ico" :get (conj common-interceptors (middlewares/file-info) (middlewares/file "static"))]
              ["/login" :get (conj common-interceptors `login-page)]
              ["/about" :get (conj common-interceptors `about-page)]})

(def keystore-password (apply str less.awful.ssl/key-store-password))
(def keystore-instance
  (less.awful.ssl/key-store (::config/private config/params)
                            (::config/public config/params)))

; (.setKeyEntry keystore-instance "cert2" (less.awful.ssl/private-key "./letsencrypt/live/authfed.com/privkey.pem") less.awful.ssl/key-store-password (less.awful.ssl/load-certificate-chain "./letsencrypt/live/authfed.com/fullchain.pem"))
; (.reload ssl-context-factory (reify java.util.function.Consumer (accept [this _] _)))

(def ssl-context-factory
  (let [^SslContextFactory context (SslContextFactory.)]
    (.setKeyStore context keystore-instance)
    (.setKeyStorePassword context keystore-password)
    (.setCipherComparator context HTTP2Cipher/COMPARATOR)
    (.setUseCipherSuitesOrder context true)
    context))

(def service
  {:env :prod
   ::http/routes routes
   ::http/resource-path "/public"
   ::http/type :jetty
   ::http/host "0.0.0.0"
   ::http/port 8080
   ::http/container-options {:h2c? true
                             :h2? false
                             :ssl-context-factory ssl-context-factory
                             :ssl-port 8443
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
