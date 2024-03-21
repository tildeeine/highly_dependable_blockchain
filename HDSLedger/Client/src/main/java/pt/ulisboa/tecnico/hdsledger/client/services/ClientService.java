package pt.ulisboa.tecnico.hdsledger.client.services;

import java.io.IOException;
import java.net.InetAddress;
import java.text.MessageFormat;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import pt.ulisboa.tecnico.hdsledger.communication.ClientData;
import pt.ulisboa.tecnico.hdsledger.communication.ClientMessage;
import pt.ulisboa.tecnico.hdsledger.communication.Link;
import pt.ulisboa.tecnico.hdsledger.communication.Message;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;
import pt.ulisboa.tecnico.hdsledger.client.models.Wallet;

public class ClientService implements UDPServiceClient {

    private static final CustomLogger LOGGER = new CustomLogger(ClientService.class.getName());
    // Nodes configurations
    private final ProcessConfig[] nodesConfig;
    // Current node is leader
    private final ProcessConfig config;

    // Link to communicate with nodes
    private final Link link;

    private Timer timer;

    private int timeout;

    private int allowedFaults;

    private Wallet wallet;

    // < requestID, confirmationMessage count>
    private Map<Integer, Integer> requestTracker = new ConcurrentHashMap<>();

    public ClientService(Link link, ProcessConfig config,
            ProcessConfig leaderConfig, ProcessConfig[] nodesConfig, Wallet wallet) {

        this.link = link;
        this.config = config;
        this.nodesConfig = nodesConfig;
        this.allowedFaults = numberOfFaults(nodesConfig.length);
        System.out.println(this.allowedFaults);
        this.timeout = 5000;
        this.wallet = wallet;
    }

    public void sendClientMessage(ClientMessage clientMessage) { // ! legacy from part1
        this.requestTracker.put(clientMessage.getClientData().getRequestID(), 0);
        link.broadcast(clientMessage);
    }

    public void clientTransfer(ClientMessage transferMessage) {
        // Create a message
        this.requestTracker.put(transferMessage.getClientData().getRequestID(), 0);
        link.broadcast(transferMessage);
    }

    public void checkBalance(ClientMessage balanceRequest) {
        // Create message with balance request
        String userKey = balanceRequest.getClientData().getValue();
        link.broadcast(balanceRequest);
    }

    public void handleBalanceResponse(ClientMessage balanceResponse) {
        // Check if the balance response is for this client
        if (balanceResponse.getClientData().getClientID().equals(this.config.getId())) {
            // Update the balance
            this.wallet.setBalance(Float.parseFloat(balanceResponse.getClientData().getValue()));
            // Return value to user
        }
    }

    public ProcessConfig getConfig() {
        return this.config;
    }

    public void cancelTimer() {
        if (timer != null)
            timer.cancel();
    }

    private void startTimer() { // ! Never used
        cancelTimer();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                System.out.println("Task timed out!");
            }
        };

        timer = new Timer(true); // Daemon thread
        timer.schedule(task, timeout); // 1000 milliseconds = 1 second
    }

    public static int numberOfFaults(int N) {
        return (N - 1) / 3;
    }

    private void handleConfirmationMessage(ClientMessage clientMessage) {
        ClientData clientData = clientMessage.getClientData();
        String clientID = clientData.getClientID();
        int requestID = clientData.getRequestID();

        if (!clientID.equals(this.config.getId())) {
            return;
        }
        // Check if request id is in requestTracker map.
        // Increment value with one
        if (requestTracker.containsKey(requestID)) {
            int count = requestTracker.getOrDefault(requestID, 0) + 1;
            requestTracker.put(requestID, count);
            if (count == this.allowedFaults + 1) {
                LOGGER.log(Level.INFO, MessageFormat.format(
                        "{0} - Recieved {1} valid confirmations on transaction. Transaction appended to blockchain.",
                        config.getId(), count, clientMessage.getMessageId()));
            }
        }
    }

    @Override
    public void listen() {
        try {
            // Thread to listen on every request
            new Thread(() -> {
                try {
                    System.out.println(
                            "Listening on " + InetAddress.getLocalHost().getHostAddress() + ":" + config.getPort());
                    // remove
                    while (true) {
                        Message message = link.receive();
                        // non verified messages
                        if (message == null)
                            return;

                        // Separate thread to handle each message
                        new Thread(() -> {

                            switch (message.getType()) {

                                case ACK ->
                                    LOGGER.log(Level.INFO, MessageFormat.format("{0} - Received ACK message from {1}",
                                            config.getId(), message.getSenderId()));

                                case IGNORE ->
                                    LOGGER.log(Level.INFO,
                                            MessageFormat.format("{0} - Received IGNORE message from {1}",
                                                    config.getId(), message.getSenderId()));

                                case CLIENT_CONFIRMATION -> {

                                    LOGGER.log(Level.INFO,
                                            MessageFormat.format(
                                                    "{0} - Received CLIENT_CONFIRMATION message from {1}",
                                                    config.getId(), message.getSenderId()));

                                    ClientMessage confirmationMessage = (ClientMessage) message;
                                    handleConfirmationMessage(confirmationMessage);
                                }

                                case BALANCE -> {
                                    LOGGER.log(Level.INFO,
                                            MessageFormat.format("{0} - Received BALANCE message from {1}",
                                                    config.getId(), message.getSenderId()));

                                    handleBalanceResponse((ClientMessage) message);
                                }

                                default ->
                                    LOGGER.log(Level.INFO,
                                            MessageFormat.format("{0} - Received unknown message from {1}",
                                                    config.getId(), message.getSenderId()));

                            }

                        }).start();
                    }
                } catch (IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
