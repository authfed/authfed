(ns authfed.template
 (:require [clojure.string :as string]))

(def title
 {:tag "title" :content "Authfed"})

(def meta-charset
 {:tag "meta" :attrs {:charset "utf-8"}})

(def meta-width
 {:tag "meta" :attrs {:content "width=device-width, initial-scale=1" :name "viewport"}})

(def stylesheet
 {:tag "link" :attrs {:rel "stylesheet" :href "/css/bootstrap.min.css"}})

(defn scripts []
 ; {:tag "div"
 ;  :content [{:tag "script"
 ;             :attrs {:src "https://code.jquery.com/jquery-3.2.1.slim.min.js"
 ;           		        :integrity "sha384-KJ3o2DKtIkvYIK3UENzmM7KCkRr/rE9/Qpg6aAZGJwFDMVNA/GpGFF93hXpG5KkN"
 ;           		        :crossorigin "anonymous"}
 ;             :content " "}
 ;            {:tag "script"
 ;             :attrs {:src "https://cdnjs.cloudflare.com/ajax/libs/popper.js/1.12.9/umd/popper.min.js"
 ;           		        :integrity "sha384-ApNbgh9B+Y1QKtv3Rn7W3mgPxhU9K/ScQsAP7hUibX39j7fakFPskvXusvfa0b4Q"
 ;           		        :crossorigin "anonymous"}
 ;             :content " "}
 ;            {:tag "script"
 ;             :attrs {:src "https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0/js/bootstrap.min.js"
 ;           		        :integrity "sha384-JZR6Spejh4U02d8jOt6vLEHfe/JQGiRRSQQxSfFWpi1MquVdAyjUar5+76PVCmYl"
 ;           		        :crossorigin "anonymous"}
 ;             :content " "}]}
)


(def font-stylesheet
 {:tag "link"
  :attrs {:rel "stylesheet"
		        :href "https://fonts.googleapis.com/css?family=Poppins%3A400%2C700%7CLato%3A400%2C700%2C400italic%2C700italic%7CInconsolata%3A400%2C700&amp;subset=latin%2Clatin-ext"
		        :crossorigin "anonymous"}})

(defn nav [request]
 (let [uri (:uri request)
       logged-in? (contains? (:session request) :email)]
  {:tag :div :attrs {:class "container" :style "margin-top: 20px;"}
   :content [{:tag :ul :attrs {:class "nav nav-pills"}
              :content
              [{:tag :li :attrs {:class "nav-item"}
                :content
                [{:tag :a
                  :attrs {:class (if (= "/" uri) "nav-link active" "nav-link") :href "/"}
                  :content
                  ["Home"]}]}
               {:tag :li :attrs {:class "nav-item"}
                :content
                [{:tag :a
                  :attrs {:class (if (= "/aws" uri) "nav-link active" "nav-link") :href "/aws"}
                  :content ["AWS"]}]}
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
		            :content [(nav request)
                        {:tag "div"
                         :attrs {:class "container"
                                 :style "margin-top: 40px;"}
                         :content body}
                        (scripts)]}]}))

(defn input
 [{:keys [id type label value class]}]
 (if label
  {:tag "div" :attrs {:class "input-group mb-3"}
   :content [{:tag "div" :attrs {:class "input-group-prepend"}
              :content [{:tag "span"
                         :attrs {:for id
                                 :id (str "label-" id)
                                 :class "input-group-text"}
                         :content [label]}]}
             {:tag "input"
              :attrs {:id id :name id
                      :type type
                      :value value
                      :class (string/join " " (conj class "form-control"))}}]}
  {:tag "div" :attrs {:class "input-group mb-3"}
   :content [{:tag "input"
              :attrs {:id id :name id
                      :type type
                      :value value
                      :class (string/join " " (conj class "form-control"))}}]}))
