(ns authfed.config
 (:import [java.io File FileNotFoundException FileReader PushbackReader])
 (:require [clojure.edn :as edn]
           [clojure.data.json :as json]))

(def private-key
 (let [f (new File "/etc/letsencrypt/live/authfed.net/privkey.pem")]
  (try (.getPath (doto f slurp))
   (catch FileNotFoundException e "dummy-private.pem"))))

(def public-key
 (let [f (new File "/etc/letsencrypt/live/authfed.net/fullchain.pem")]
  (try (.getPath (doto f slurp))
   (catch FileNotFoundException e "dummy-public.pem"))))

(def users
 (with-open [fr (new PushbackReader (new FileReader "users.edn"))]
  (edn/read fr)))

(def static
 (let [directory (new File "/etc/authfed/static")]
  (try (.getPath (doto directory .listFiles))
   (catch FileNotFoundException e "static"))))

(def account-id
 (let [endpoint "http://169.254.169.254/latest/meta-data/iam/info"]
  (try (-> (slurp endpoint)
           json/read-str
           (get "InstanceProfileArn")
           (.substring 13 25))
   (catch Exception e "1234"))))

(def params
 {::private private-key
  ::public public-key
  ::users users
  ::static static
  ::saml-role-mapping (str "arn:aws:iam::" account-id ":role/test20200424,arn:aws:iam::" account-id ":saml-provider/authfed-net")})
