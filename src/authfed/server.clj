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

(def routes #{["/" :get (conj common-interceptors `home-page)]
              ["/favicon.ico" :get (conj common-interceptors (middlewares/file-info) (middlewares/file "static"))]
              ["/login" :any (conj common-interceptors `login-page)]
              ["/logout" :any (conj common-interceptors `logout-page)]
              ["/aws" :get (conj common-interceptors `aws-page)]
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
