(ns authfed.template
 (:require [clojure.string :as string]
           [clojure.pprint :as pprint]))

(def title
 {:tag "title" :content "Authfed"})

(def meta-charset
 {:tag "meta" :attrs {:charset "utf-8"}})

(def meta-width
 {:tag "meta" :attrs {:content "width=device-width, initial-scale=1" :name "viewport"}})

(def stylesheet
 {:tag "link" :attrs {:rel "stylesheet" :href "/css/bootstrap.min.css"}})

(def font-stylesheet
 {:tag "link"
  :attrs {:rel "stylesheet"
          :href "https://fonts.googleapis.com/css?family=Poppins%3A400%2C700%7CLato%3A400%2C700%2C400italic%2C700italic%7CInconsolata%3A400%2C700&amp;subset=latin%2Clatin-ext"
          :crossorigin "anonymous"}})

(defn flash-message [request]
 {:tag "div"
  :content (vector (when-let [error (:error (:flash request))]
                    {:tag :div :attrs {:class "alert alert-danger alert-dismissible"}
                     :content [{:tag :button :attrs {:type "button" :class "close"
                                                     :onclick "this.parentElement.remove();"}
                                :content ["×"]}
                               error]})
                   (when-let [info (:info (:flash request))]
                    {:tag :div :attrs {:class "alert alert-success alert-dismissible"}
                     :content [{:tag :button :attrs {:type "button" :class "close"
                                                     :onclick "this.parentElement.remove();"}
                                :content ["×"]}
                               info]}))})

(defn nav [request]
 (let [uri (:uri request)
       logged-in? (and (contains? (:session request) :email)
                       (contains? (:session request) :mobile))]
  {:tag :div :attrs {:class "container" :style "margin-top: 20px;"}
   :content [{:tag :ul :attrs {:class "nav nav-pills"}
              :content
              [{:tag :li :attrs {:class "nav-item"}
                :content
                [{:tag :a
                  :attrs {:class (if (= "/apps" uri) "nav-link active" "nav-link") :href "/apps"}
                  :content ["Apps"]}]}
               {:tag :li :attrs {:class "nav-item"}
                :content
                [{:tag :a
                  :attrs {:class (if (= "/start" uri) "nav-link active" "nav-link") :href "/start"}
                  :content ["Start"]}]}
               {:tag :li :attrs {:class "nav-item"}
                :content
                [{:tag :a
                  :attrs {:class (if (= "/next-challenge" uri) "nav-link active" "nav-link") :href "/next-challenge"}
                  :content ["Next challenge"]}]}
               {:tag :li :attrs {:class "nav-item"}
                :content
                [{:tag :a
                  :attrs {:class (if (#{"/logout" "/login"} uri) "nav-link active" "nav-link") :href (if logged-in? "/logout" "/login")}
                  :content [(if logged-in? "Logout" "Login")]}]}]}]}))

(defn html
 ([] (html {} nil))
 ([request body]
  {:tag "html"
   :content [{:tag "head"
              :content [title meta-charset meta-width stylesheet font-stylesheet]}
             {:tag "body"
              :content [;; {:tag "pre" :attrs {:style "width:100%;"} :content [(with-out-str (pprint/pprint (:session request)))]}
                        ;; (nav request)
                        {:tag "div"
                         :attrs {:class "container"
                                 :style "margin-top: 40px;"}
                         :content (cons (flash-message request) body)}]}]}))

(defn input
 [{:keys [id label classes] :as params}]
 (if label
  {:tag "div" :attrs {:class "input-group mb-3"}
   :content [{:tag "div" :attrs {:class "input-group-prepend"}
              :content [{:tag "span"
                         :attrs {:for id
                                 :id (str "label-" id)
                                 :class "input-group-text"}
                         :content [label]}]}
             {:tag "input"
              :attrs (merge (select-keys params [:id :type :value :autofocus :disabled])
                            {:name id}
                            {:class (string/join " " (conj classes "form-control"))})}]}
  {:tag "div" :attrs {:class "input-group mb-3"}
   :content [{:tag "input"
              :attrs (merge (select-keys params [:id :type :value :autofocus :disabled])
                            {:name id}
                            {:class (string/join " " (conj classes "form-control"))})}]}))
