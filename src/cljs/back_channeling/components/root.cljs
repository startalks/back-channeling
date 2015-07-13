(ns back-channeling.components.root
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan timeout]]
            [back-channeling.routing :as routing]
            [back-channeling.api :as api]
            [back-channeling.socket :as socket]
            [back-channeling.notification :as notification]
            [back-channeling.components.avatar :refer [avatar]])
  (:use [back-channeling.components.board :only [board-view]]
        [back-channeling.components.curation :only [curation-page]]
        [back-channeling.component-helper :only [make-click-outside-fn]]
        [cljs.reader :only [read-string]]))

(defn refresh-board [app board-name]
  (api/request (str "/api/board/" board-name)
               {:handler (fn [response]
                           (let [new-board (update-in response [:board/threads]
                                                      (fn [threads]
                                                        (->> threads
                                                             (map (fn [t] {(:db/id t) t}))
                                                             (reduce merge {}))))]
                             (if (get-in @app [:boards board-name])
                               (om/transact! app [:boards board-name]
                                             (fn [board]
                                               (update-in new-board [:board/threads]
                                                          #(merge-with merge % (:board/threads board)))))
                               
                               (om/update! app [:boards board-name] new-board))))}))

(defn fetch-comments
  ([app thread]
   (fetch-comments app thread 1))
  ([app {:keys [board/name db/id]} from]
   (api/request (str "/api/thread/" id "/comments/" from "-")
                {:handler (fn [fetched-comments]
                            (om/transact! app [:boards "default" :board/threads id :thread/comments]
                                          (fn [comments]
                                            (vec (concat comments fetched-comments)))))})))

(defn refresh-thread [app thread]
  (om/transact! app [:boards (:board/name thread) :board/threads (:db/id thread)]
                #(assoc %
                        :thread/last-updated (:thread/last-updated thread)
                        :thread/resnum (:thread/resnum thread)))
  (when (= (:target-thread @app) (:db/id thread))
    (fetch-comments app thread
                    (inc (count (get-in @app [:boards "default" :board/threads (:db/id thread) :thread/comments] [])) ))))

(defn search-threads [owner board-name query]
  (api/request (str "/api/board/" board-name "/threads?q=" (js/encodeURIComponent query))
               {:handler (fn [results]
                           (om/set-state! owner :search-result results))}))

(defcomponent root-view [app owner]
  (init-state [_]
    {:open-profile? false
     :open-users? false
     :search-result nil
     :board-channel (chan)
     :user {:user/name  (.. js/document (querySelector "meta[property='bc:user:name']") (getAttribute "content"))
            :user/email (.. js/document (querySelector "meta[property='bc:user:email']") (getAttribute "content"))}
     :called-message nil
     :click-outside-fn nil})
  
  (will-mount [_]
    (routing/init app owner)
    (refresh-board app "default")
    (api/request "/api/users"
                 {:handler (fn [response]
                             (om/update! app :users (apply hash-set response)))})
    (socket/open (str "ws://" (.-host js/location) "/ws")
                 :on-open (fn []
                            (socket/send :join (om/get-state owner :user)))
                 :on-message (fn [message]
                               (let [[cmd data] (read-string message)]
                                 (case cmd
                                   :notify (notification/show data)
                                   :update-board (refresh-board app "default")
                                   :update-thread (refresh-thread app (assoc data :board/name "default"))
                                   :join  (om/transact! app [:users] #(conj % data))
                                   :leave (om/transact! app [:users] #(disj % data))
                                   :call  (js/alert (:message data))))))
    (when-let [on-click-outside (om/get-state owner :click-outside-fn)]
      (.removeEventListener js/document "mousedown" on-click-outside)))

  (did-mount [_]
    (when-not (om/get-state owner :click-outside-fn)
      (om/set-state! owner :click-outside-fn
                   (make-click-outside-fn
                    (.. (om/get-node owner) (querySelector "div.site.menu"))
                    (fn [_]
                      (om/update-state! owner #(assoc %
                                                      :open-profile? false
                                                      :open-users? false
                                                      :search-result nil))))))
    (.addEventListener js/document "mousedown"
                       (om/get-state owner :click-outside-fn)))

  (render-state [_ {:keys [open-profile? open-users? search-result user board-channel]}]
    (html
     [:div.full.height
      [:div.ui.fixed.site.menu
       [:div.item
        [:a {:href "#/"}
         [:img.ui.logo.image {:src "/img/logo.png" :alt "Back Channeling"}]]]
       [:div.center.menu
        [:div.item
         [:div.ui.search
          [:div.ui.icon.input
           [:input#search.prompt
            {:type "text"
             :placeholder "Keyword"
             :on-key-up (fn [_]
                         (if-let [query (.. js/document (getElementById "search") -value)]
                           (if (> (count query) 2)
                             (search-threads owner "default" query)
                             (om/set-state! owner :search-result nil))))}]
           [:i.search.icon]]
          (when (not-empty search-result)
            [:div.results.transition.visible
             (for [res search-result]
               [:a.result {:on-click
                           (fn [_]
                             (om/set-state! owner :search-result nil)
                             (set! (.-href js/location) (str "#/board/" (:board/name res) "/" (:db/id res))))}
                [:div.content
                 [:div.title (:thread/title res)]]])])]]]
       
       [:div.right.menu
        [:a.item
         [:div {:on-click (fn [_]
                            (om/set-state! owner :open-users? (not open-users?)))}
          [:i.users.icon]
          [:floating.ui.label (count (:users app))]]
         (when open-users?
           [:div.ui.flowing.popup.left.bottom.transition.visible {:style {:top "60px"}}
            [:ui.grid
             (for [member (:users app)]
               [:column {:on-click (fn [_]
                                     (socket/send :call {:from user
                                                         :to #{member}
                                                         :message (str (:user/name user) " is calling!!")}))}
                (om/build avatar (:user/email member))])]])]
        [:div.ui.dropdown.item
         [:div {:on-click (fn [_]
                            (om/set-state! owner :open-profile? (not open-profile?)))}
          (om/build avatar (:user/email user))
          [:span (:user/name user)] ]
         [:div.menu.transition {:class (if open-profile? "visible" "hidden")} 
          [:a.item {:href "/logout"} "Logout"]]]]]
      (when-let [board (get-in app [:boards "default"])]
        (case (:page app)
          :board (om/build board-view board
                           {:init-state {:channel board-channel}
                            :state {:target-thread (:target-thread app)
                                    :target-comment (:target-comment app)}
                            :opts {:user user}})
          :curation (om/build curation-page (:curating-blocks app)
                              {:init-state {:thread (get-in board [:board/threads (:target-thread app)])}
                               :opts {:user user}})))])))
