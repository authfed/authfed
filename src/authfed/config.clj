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
 (with-open [fr (new PushbackReader (new FileReader (or-dummy "/etc/authfed/users.edn" "config/users.edn")))]
  (edn/read fr)))

(def targets
 (with-open [fr (new PushbackReader (new FileReader (or-dummy "/etc/authfed/targets.edn" "config/targets.edn")))]
  (edn/read fr)))

(def saml
 (with-open [fr (new PushbackReader (new FileReader (or-dummy "/etc/authfed/saml.edn" "config/saml.edn")))]
  (edn/read fr)))

(def mac? (-> (System/getProperty "os.name") .toLowerCase (.startsWith "mac")))

; (def account-id
;  (let [endpoint (when-not mac? "http://169.254.169.254/latest/meta-data/iam/info")]
;   (try (-> (slurp endpoint)
;            json/read-str
;            (get "InstanceProfileArn")
;            (.substring 13 25))
;    (catch Exception e "1234"))))

(def params
 {::hostname (if mac? "localhost" "authfed.net")
  ::letsencrypt (if mac?
                 ["dummy"]
                 (->> (new java.io.File "/etc/authfed")
                  .listFiles
                  (map #(.getName %))
                  (filter #(or (.endsWith % "-fullchain.pem")
                               (.endsWith % "-privkey.pem")))
                  sort
                  (apply hash-map)
                  keys
                  (map #(.replace % "-fullchain.pem" ""))
                  (map #(str "/etc/authfed/" %))
                  (into [])))
  ::http-port (if mac? 8080 80)
  ::ssl-port (if mac? 8443 443)})
