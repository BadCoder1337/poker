(ns poker.discord.bot
  (:require [poker.logic.game :as poker]
            [poker.logic.core :as cards]
            [poker.discord.display :as disp]
            [discljord.connections :as conns]
            [discljord.messaging :as msgs]
            [discljord.events :as events]
            [clojure.core.async :as async]
            [clojure.string :as strings]
            [clojure.edn :as edn]))


(defonce message-ch (atom nil))
(defonce timeout (atom nil))
(defonce default-buy-in (atom nil))

(defonce active-games (atom {}))
(defonce waiting-channels (atom #{}))
(defonce users-in-game (atom #{}))

(defn calculate-budgets [players buy-in previous-budgets]
  (merge (zipmap players (repeat buy-in)) previous-budgets))

(defn shuffled-deck []
  (shuffle (cards/deck)))

(defn send-message! [channel-id content]
  (msgs/create-message! @message-ch channel-id :content content))

(defn game-loop [game]
  (async/go-loop [{:keys [state channel-id move-channel] :as game} game]
    (swap! active-games assoc channel-id game)
    (send-message! channel-id (disp/game-state-message game))
    (if (poker/end? game)
      (do
        (send-message! channel-id ((case state :instant-win disp/instant-win-message :showdown disp/showdown-message) game))
        (swap! active-games dissoc channel-id)
        game)
      (do
        (send-message! channel-id (disp/turn-message game))
        (let [{:keys [action amount]} (async/<! move-channel)]
          (recur (case action
                   (:check :call) (poker/call game)
                   :all-in (poker/all-in game)
                   :fold (poker/fold game)
                   :raise (poker/raise game amount))))))))

(defn gather-players! [channel-id message-id]
  (msgs/create-reaction! @message-ch channel-id message-id disp/handshake-emoji)
  (async/go
    (async/<! (async/timeout @timeout))
    (->> @(msgs/get-reactions! @message-ch channel-id message-id disp/handshake-emoji :limit 20)
         (remove :bot)
         (map :id)
         (remove @users-in-game))))

(defn notify-players! [{:keys [players] :as game}]
  (doseq [player players
          :let [{dm-id :id} @(msgs/create-dm! @message-ch player)]]
    (send-message! dm-id (disp/player-notification-message game player))))

(defn in-game? [user-id]
  (some #(contains? % user-id) (map :players (vals @active-games))))

(defn start-game! [channel-id buy-in start-message start-fn]
  (let [{join-message-id :id} @(send-message! channel-id start-message)]
    (swap! waiting-channels conj channel-id)
    (async/go
      (let [players (async/<! (gather-players! channel-id join-message-id))]
        (swap! waiting-channels disj channel-id)
        (if (> (count players) 1)
          (let [game (assoc (start-fn players) :channel-id channel-id :move-channel (async/chan))]
            (notify-players! game)
            (let [{:keys [budgets] :as result} (async/<! (game-loop game))]
              (start-game!
                channel-id buy-in
                (disp/restart-game-message result @timeout buy-in)
                #(poker/restart-game result % (shuffled-deck) (calculate-budgets % buy-in budgets)))))
          (msgs/edit-message! @message-ch channel-id join-message-id :content "Not enough players."))))))

(defmulti handle-event (fn [type _] type))

(defn try-parse-int [str]
  (try
    (Integer/parseInt str)
    (catch NumberFormatException _ nil)))

(defmulti
  handle-command
  (fn [command _ _ _]
    (strings/lower-case command)))

(defn valid-move [{[current] :cycle :as game} user-id command]
  (and (= current user-id) (some #(and (= command (name (:action %))) %) (poker/possible-moves game))))

(doseq [move-cmd ["fold" "check" "call" "all-in"]]
  (defmethod handle-command move-cmd [_ _ user-id channel-id]
    (let [{:keys [move-channel] :as game} (get @active-games channel-id)]
      (when-let [move (valid-move game user-id move-cmd)]
        (async/>!! move-channel move)))))

(defmethod handle-command "raise" [_ args user-id channel-id]
  (let [{:keys [move-channel] :as game} (get @active-games channel-id)]
    (when-let [move (valid-move game user-id "raise")]
      (if-let [amount (try-parse-int (args 0))]
        (if (<= (poker/minimum-raise game) amount (poker/possible-bet game))
          (async/>!! move-channel (assoc move :amount amount))
          (send-message! channel-id (disp/invalid-raise-message game)))
        (send-message! channel-id (disp/invalid-raise-message game))))))

(defmethod handle-command "holdem!" [_ args user-id channel-id]
  (cond
    (contains? @active-games channel-id) (send-message! channel-id (disp/channel-occupied-message channel-id user-id))
    (contains? @waiting-channels channel-id) (send-message! channel-id (disp/channel-waiting-message channel-id user-id))
    (in-game? user-id) (send-message! channel-id (disp/already-ingame-message user-id))
    :else (let [buy-in (or (and (seq args) (try-parse-int (args 0))) @default-buy-in)
                big-blind (quot buy-in 100)]
            (start-game!
              channel-id buy-in
              (disp/new-game-message user-id @timeout buy-in)
              #(poker/start-new-game big-blind % (shuffled-deck) (calculate-budgets % buy-in {}))))))

(defmethod handle-command :default [_ _ _ _])

(defmethod handle-event :message-create
  [_ {{author-id :id} :author :keys [channel-id content]}]
  (let [split (strings/split content #"\s+")]
    (handle-command (first split) (subvec split 1) author-id channel-id)))

(defn def-ping-commands [bot-id]
  (let [mention (disp/user-mention bot-id)]
    (doseq [command [mention (strings/replace mention "@" "@!")]]
      (defmethod handle-command command [_ _ user-id channel-id]
        (send-message! channel-id (disp/info-message user-id))))))

(defn- start-bot! [{:keys [token timeout default-buy-in]}]
  (let [event-ch (async/chan 100)
        connection-ch (conns/connect-bot! token event-ch)
        message-ch (msgs/start-connection! token)]
    (reset! poker.discord.bot/message-ch message-ch)
    (reset! poker.discord.bot/timeout timeout)
    (reset! poker.discord.bot/default-buy-in default-buy-in)
    (def-ping-commands (:id @(msgs/get-current-user! message-ch)))
    (events/message-pump! event-ch handle-event)
    (msgs/stop-connection! message-ch)
    (conns/disconnect-bot! connection-ch)))

(defn -main [& args]
  (start-bot! (edn/read-string (slurp "./config.clj"))))