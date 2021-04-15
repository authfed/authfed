(ns authfed.config
 (:import [java.io File FileNotFoundException FileReader PushbackReader])
 (:require [clojure.edn :as edn]
           [clojure.data.json :as json]))

(def basedir "/etc/authfed/")

(defn- load-config
 "Load from /etc/authfed if possible or config/ as a fallback."
 [s]
 (let [prod (str basedir s)
       dev  (str "config/" s)
       path (try (.getPath (doto (new File prod) slurp))
             (catch FileNotFoundException _ dev))]
  (with-open [fr (new PushbackReader (new FileReader path))]
   (edn/read fr))))

(def users (load-config "users.edn"))
(def targets (load-config "targets.edn"))
(def saml (load-config "saml.edn"))
(def sms (load-config "sms.edn"))
(def email (load-config "email.edn"))

(def mac? (-> (System/getProperty "os.name") .toLowerCase (.startsWith "mac")))

; (def account-id
;  (let [endpoint (when-not mac? "http://169.254.169.254/latest/meta-data/iam/info")]
;   (try (-> (slurp endpoint)
;            json/read-str
;            (get "InstanceProfileArn")
;            (.substring 13 25))
;    (catch Exception e "1234"))))

(def params
 {::hostname (if mac? "localhost" "login.structmap.com")
  ::letsencrypt (if mac?
                 ["dummy"]
                 (->> (new java.io.File basedir)
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
