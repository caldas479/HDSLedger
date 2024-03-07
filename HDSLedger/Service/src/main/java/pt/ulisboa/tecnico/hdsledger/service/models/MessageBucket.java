package pt.ulisboa.tecnico.hdsledger.service.models;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.text.html.Option;
import org.apache.commons.lang3.tuple.Pair;

import pt.ulisboa.tecnico.hdsledger.communication.CommitMessage;
import pt.ulisboa.tecnico.hdsledger.communication.ConsensusMessage;
import pt.ulisboa.tecnico.hdsledger.communication.PrepareMessage;
import pt.ulisboa.tecnico.hdsledger.communication.RoundChangeMessage;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;

public class MessageBucket {

    private static final CustomLogger LOGGER = new CustomLogger(MessageBucket.class.getName());
    // Quorum size
    private final int quorumSize;
    private int f;
    // Instance -> Round -> Sender ID -> Consensus message
    private final Map<Integer, Map<Integer, Map<String, ConsensusMessage>>> bucket = new ConcurrentHashMap<>();

    public MessageBucket(int nodeCount) {
        f = Math.floorDiv(nodeCount - 1, 3);
        quorumSize = Math.floorDiv(nodeCount + f, 2) + 1;
    }

    /*
     * Add a message to the bucket
     * 
     * @param consensusInstance
     * 
     * @param message
     */
    public void addMessage(ConsensusMessage message) {
        int consensusInstance = message.getConsensusInstance();
        int round = message.getRound();

        bucket.putIfAbsent(consensusInstance, new ConcurrentHashMap<>());
        bucket.get(consensusInstance).putIfAbsent(round, new ConcurrentHashMap<>());
        bucket.get(consensusInstance).get(round).put(message.getSenderId(), message);
    }

    public Optional<String> hasValidPrepareQuorum(String nodeId, int instance, int round) {
        // Create mapping of value to frequency
        HashMap<String, Integer> frequency = new HashMap<>();
        bucket.get(instance).get(round).values().forEach((message) -> {
            PrepareMessage prepareMessage = message.deserializePrepareMessage();
            String value = prepareMessage.getValue();
            frequency.put(value, frequency.getOrDefault(value, 0) + 1);
        });

        // Only one value (if any, thus the optional) will have a frequency
        // greater than or equal to the quorum size
        return frequency.entrySet().stream().filter((Map.Entry<String, Integer> entry) -> {
            return entry.getValue() >= quorumSize;
        }).map((Map.Entry<String, Integer> entry) -> {
            return entry.getKey();
        }).findFirst();
    }

    public Optional<String> hasValidCommitQuorum(String nodeId, int instance, int round) {
        // Create mapping of value to frequency
        HashMap<String, Integer> frequency = new HashMap<>();
        bucket.get(instance).get(round).values().forEach((message) -> {
            CommitMessage commitMessage = message.deserializeCommitMessage();
            String value = commitMessage.getValue();
            frequency.put(value, frequency.getOrDefault(value, 0) + 1);
        });

        // Only one value (if any, thus the optional) will have a frequency
        // greater than or equal to the quorum size
        return frequency.entrySet().stream().filter((Map.Entry<String, Integer> entry) -> {
            return entry.getValue() >= quorumSize;
        }).map((Map.Entry<String, Integer> entry) -> {
            return entry.getKey();
        }).findFirst();
    }

    // check if every (pr, pv) pair from each prepare messages is equal to (pr, pv) from the highest prepared round
    public boolean checkHighestPrepared(int instance, int round, int highestPreparedRound, String highestPreparedValue) {
        return bucket.get(instance).get(round).values().stream().allMatch((message) -> {
            int prepareRound = message.getRound();
            PrepareMessage prepareMessage = message.deserializePrepareMessage();
            return prepareRound == highestPreparedRound && prepareMessage.getValue().equals(highestPreparedValue);
        });
    }

    public Boolean hasValidRoundChangeQuorum(String nodeId, int instance, int round) {
        // Check if the number of messages of the current instance from currentRound to Round is bigger than the quorum size
        System.out.println("Return has round change quorum size: " + bucket.get(instance).get(round).size() + " quorum size: " + quorumSize + " nodeId: " + nodeId + " round: " + round);
        
        return bucket.get(instance).get(round).size() >= quorumSize;
    }

    public Map<String, ConsensusMessage> getMessages(int instance, int round) {
        return bucket.get(instance).get(round);
    }

    public int getRoundChangeMinRound(int instance, int currentRound, int round) {
        int minRound = round;
        if (bucket.get(instance).get(round).size() >= f+1 && round > currentRound) {
            for (int i = currentRound + 1; i <= round; i++) {
                if (bucket.get(instance).containsKey(i)) {
                    minRound = i;
                    break;
                }
            }
            return minRound;
        } else {
            return -1;
        }
    }

    // Helper function that returns a tuple (pr, pv) where pr and pv are, respectively, the prepared round and the prepared value of the ROUND-CHANGE message in Qrc with the highest prepared round
    public Optional<Pair<Integer, String>> getHighestPreparedRound(int instance, int round) {
        int highestPreparedRound = -1;
        String highestPreparedValue = null;
        for (ConsensusMessage message : bucket.get(instance).get(round).values()) {
            RoundChangeMessage roundChangeMessage = message.deserializeRoundChangeMessage();
            int prj = roundChangeMessage.getPreparedRound(); 
            if (prj > highestPreparedRound) {
                highestPreparedRound = prj;
                highestPreparedValue = roundChangeMessage.getPreparedValue();
            }
        }
        return highestPreparedRound == -1 ? Optional.empty() : Optional.of(Pair.of(highestPreparedRound, highestPreparedValue));
    }
    
    // Justify the round change
    public boolean allNullRoundChange(int instance, int round) {
        // check if every prepared value and prepared round from each message from the round is null
        return bucket.get(instance).get(round).values().stream().allMatch((message) -> {
            RoundChangeMessage roundChangeMessage = message.deserializeRoundChangeMessage();
            return roundChangeMessage.getPreparedValue() == null && roundChangeMessage.getPreparedRound() == -1;
        });
        
    }
}