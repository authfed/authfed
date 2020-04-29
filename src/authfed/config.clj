(ns authfed.config
 (:import [java.io File FileNotFoundException FileReader PushbackReader])
 (:require [clojure.edn :as edn]
           [clojure.data.json :as json]))

(defn- or-dummy
 [prod dev]
 (let [f (new File prod)]
  (try (.getPath (doto f slurp))
   (catch FileNotFoundException e dev))))

(def users
 (with-open [fr (new PushbackReader (new FileReader (or-dummy "/etc/authfed/users.edn" "users.edn")))]
  (edn/read fr)))

(def account-id
 (let [endpoint "http://169.254.169.254/latest/meta-data/iam/info"]
  (try (-> (slurp endpoint)
           json/read-str
           (get "InstanceProfileArn")
           (.substring 13 25))
   (catch Exception e "1234"))))

(def params
 {::private (or-dummy "/etc/authfed/authfed.net-privkey.pem" "dummy-private.pem")
  ::public (or-dummy "/etc/authfed/authfed.net-fullchain.pem" "dummy-public.pem")
  ::static (let [directory (new File "/etc/authfed/static")]
            (try (.getPath (doto directory .listFiles))
             (catch FileNotFoundException e "static")))
  ::saml-private-key (or-dummy "/etc/authfed/aws-private.pem" "dummy-private.pem")
  ::saml-public-key (or-dummy "/etc/authfed/aws-public.pem" "dummy-public.pem")
  ::saml-role-mapping (str "arn:aws:iam::" account-id ":role/test20200424,arn:aws:iam::" account-id ":saml-provider/authfed-net")})
