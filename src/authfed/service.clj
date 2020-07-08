(ns authfed.service
  (:import [org.eclipse.jetty.util.ssl SslContextFactory]
           [org.eclipse.jetty.http2 HTTP2Cipher])
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]
            [authfed.core :as core]
            [authfed.config :as config]))

(def interceptors core/common-interceptors)

(def routes
 (route/expand-routes
  [[:catch-all ["/" {:get `core/apex-redirects}]]
   [:config ["/config" {:get `core/config-page}]]
   [:net-authfed :https (::config/hostname config/params)
    ["/" interceptors {:get `core/home-page}]
    ["/start" interceptors {:any `core/start-page}]
    ["/challenge/:id" interceptors {:any `core/challenge-page}]
    ["/next-challenge" interceptors {:any `core/next-challenge-page}]
    ["/login" interceptors {:any `core/login-page}]
    ["/logout" interceptors {:any `core/logout-page}]
    ["/apps" (conj interceptors core/check-logged-in) {:get `core/apps-page}]
    ["/apps/:app-id" (conj interceptors core/check-logged-in) {:get `core/app-page}]]]))

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
   ::http/routes #(deref #'routes)
   ::http/resource-path "/public"
   ::http/type :jetty
   ::http/host "0.0.0.0"
   ::http/port (::config/http-port config/params)
   ::http/container-options {:h2c? true
                             :h2? false
                             :ssl-context-factory ssl-context-factory
                             :ssl-port (::config/ssl-port config/params)
                             :ssl? true}})
