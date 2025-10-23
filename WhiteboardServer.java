import java.io.*;
import java.net.*;
// NEW: For election timers
// NEW: For timer tasks
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WhiteboardServer 
{
    private static final Set<PrintWriter> clientWriters = Collections.synchronizedSet(new HashSet<>());
    private static final List<String> messageLog = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, PrintWriter> peerWriters = new ConcurrentHashMap<>();
    private static volatile TimerTask currentTimerTask; // NEW: Store the current task
    
    // --- NEW: Election State Variables ---
    private static String myAddress;
    private static String currentLeaderAddress = null;
    private static int totalServers = 0;
    private static int majority = 0;

    private static enum State { FOLLOWER, CANDIDATE, LEADER }
    private static volatile State currentState = State.FOLLOWER;
    private static volatile int currentTerm = 0; // Like a "generation" number
    private static volatile String votedFor = null;
    private static volatile int voteCount = 0;

    private static final Timer timer = new Timer(); // A single timer for heartbeats or elections
    private static final Random rand = new Random();
    private static final int ELECTION_TIMEOUT_MIN = 5000; // 5 seconds
    private static final int ELECTION_TIMEOUT_MAX = 10000; // 10 seconds
    private static final int HEARTBEAT_INTERVAL = 2000; // 2 seconds
    // --- End of New Variables ---

    private static long sequenceNumber = 0;

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java WhiteboardServer <my-port> <my-address> <peer-list>");
            System.out.println("Example: java WhiteboardServer 5001 localhost:5001 localhost:5002,localhost:5003");
            return;
        }

        int port = Integer.parseInt(args[0]);
        myAddress = args[1];
        String[] peerAddresses = args[2].split(",");

        // NEW: Calculate majority
        totalServers = peerAddresses.length + 1; // Peers + self
        majority = (totalServers / 2) + 1;
        System.out.println("Cluster size: " + totalServers + ", Majority needed: " + majority);

        System.out.println("Whiteboard Server starting on " + myAddress + " as FOLLOWER.");

        for (String peerAddr : peerAddresses) {
            if (!peerAddr.isBlank()) {
                Thread t = new Thread(new PeerConnector(peerAddr));
                t.setDaemon(true);
                t.start();
            }
        }
        
        // NEW: Start the election process
        resetElectionTimer();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket socket = serverSocket.accept();
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String handshake = in.readLine();
                    
                    if (handshake == null) throw new IOException("Empty handshake");

                    if ("IAM:CLIENT".equals(handshake)) {
                        System.out.println("Client connected: " + socket.getRemoteSocketAddress());
                        Thread t = new Thread(new ClientHandler(socket, in));
                        t.setDaemon(true);
                        t.start();
                    } else if (handshake.startsWith("IAM:SERVER:")) {
                        String[] parts = handshake.split(":");
                        // CHANGED: Re-assemble the full address
                        String peerAddr = parts[2] + ":" + parts[3]; 
                        System.out.println("Peer connected (inbound): " + peerAddr);
                        Thread t = new Thread(new PeerHandler(socket, in, peerAddr));
                        t.setDaemon(true);
                        t.start();
                    } else {
                        System.out.println("Unknown handshake: '" + handshake + "'. Closing.");
                        socket.close();
                    }
                } catch (IOException e) {
                    System.out.println("Handshake failed: " + e.getMessage());
                    if (socket != null && !socket.isClosed()) socket.close();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --- Client Handler (No changes from Step 2) ---
    // Note: This logic is NOT yet consistent. Clients are still writing
    // to any server, which is wrong. We fix this in the next step.
    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter out;
        private final BufferedReader in;

        ClientHandler(Socket socket, BufferedReader in) {
            this.socket = socket; this.in = in;
        }

        @Override
        public void run() {
            try {
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                synchronized(messageLog) {
                    for(String msg: messageLog) out.println(msg);
                }
                clientWriters.add(out);
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (messageLog) {
                        sequenceNumber++;
                        messageLog.add(line);
                        System.out.println("Seq: "+ sequenceNumber +" Relayed: " + line);
                    }
                    synchronized (clientWriters) {
                        for (PrintWriter pw : clientWriters) pw.println(line);
                    }
                }
            } catch (IOException e) {
                System.out.println("Client disconnected: " + socket.getRemoteSocketAddress());
            } finally {
                if (out != null) clientWriters.remove(out); 
                try { if (in != null) in.close(); } catch (IOException ignored) {}
                if (out != null) out.close();
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    // --- Peer Connector (No changes from Step 2) ---
    private static class PeerConnector implements Runnable {
        private final String peerAddress;
        private final String host;
        private final int port;

        PeerConnector(String peerAddress) {
            this.peerAddress = peerAddress;
            String[] parts = peerAddress.split(":");
            this.host = parts[0];
            this.port = Integer.parseInt(parts[1]);
        }

        @Override
        public void run() {
            while (true) {
                try (Socket socket = new Socket(host, port)) {
                    System.out.println("Peer connected (outbound): " + peerAddress);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    out.println("IAM:SERVER:" + myAddress);
                    peerWriters.put(peerAddress, out);
                    socket.getInputStream().read(); 
                } catch (IOException e) {
                    // System.out.println("Could not connect to peer " + peerAddress + ". Retrying...");
                } finally {
                    peerWriters.remove(peerAddress);
                }
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
    }

    // --- Peer Handler (CHANGED: Now processes election messages) ---
    private static class PeerHandler implements Runnable {
        private final Socket socket;
        private final BufferedReader in;
        private final String peerAddress;

        PeerHandler(Socket socket, BufferedReader in, String peerAddress) {
            this.socket = socket; this.in = in; this.peerAddress = peerAddress;
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    // NEW: Process cluster messages
                    handleClusterMessage(line, peerAddress);
                }
            } catch (IOException e) {
                System.out.println("Peer " + peerAddress + " disconnected: " + e.getMessage());
            } finally {
                try { if (in != null) in.close(); } catch (IOException ignored) {}
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    // --- NEW: Election Logic Methods ---

    /**
     * The main message router for all server-to-server communication.
     * This method is synchronized to prevent race conditions when changing state.
     */
    private static synchronized void handleClusterMessage(String line, String fromAddress) {
        try {
            String[] parts = line.split(":");
            String command = parts[0];
            int term = Integer.parseInt(parts[1]);
            String senderAddress = parts[2]+":"+parts[3];

            // Rule 1: If we receive a message with a higher term,
            // we are outdated. We must become a follower in that new term.
            if (term > currentTerm) {
                becomeFollower(term, null); // Step down
            }

            switch (command) {
                case "HEARTBEAT":
                    // Received from a valid leader
                    if (term == currentTerm && currentState != State.LEADER) {
                        currentLeaderAddress = senderAddress;
                        System.out.println("Heartbeat received from Leader " + senderAddress);
                        resetElectionTimer();
                    }
                    break;
                
                case "REQUEST_VOTE":
                    // A candidate is asking for our vote
                    if (term == currentTerm && (votedFor == null || votedFor.equals(senderAddress))) {
                        // Grant the vote
                        votedFor = senderAddress;
                        System.out.println("Voting for " + senderAddress + " in term " + term);
                        PrintWriter pw = peerWriters.get(senderAddress);
                        if (pw != null) {
                            pw.println("VOTE_GRANTED:" + currentTerm + ":" + myAddress);
                        }
                        resetElectionTimer(); // We voted, so we reset our own timer
                    }
                    break;

                case "VOTE_GRANTED":
                    // We received a vote!
                    if (term == currentTerm && currentState == State.CANDIDATE) {
                        voteCount++;
                        System.out.println("Received vote from " + senderAddress + ". Total votes: " + voteCount);
                        if (voteCount >= majority) {
                            becomeLeader();
                        }
                    }
                    break;
            }
        } catch (Exception e) {
            System.out.println("Malformed cluster message: " + line + " | " + e.getMessage());
        }
    }

    /** Broadcasts a message to all connected peers. */
    private static void broadcastToPeers(String message) {
        // System.out.println("Broadcasting: " + message);
        for (PrintWriter pw : peerWriters.values()) {
            pw.println(message);
        }
    }

    /** Resets the election timer to a new random interval. */
        private static synchronized void resetElectionTimer() 
        {
        // NEW: Cancel the *previous* timer task, if it exists
            if (currentTimerTask != null) {
                currentTimerTask.cancel();
        }
        
        // NEW: Create and store the *new* task
            currentTimerTask = new TimerTask() {
                @Override
                public void run() {
                    startElection(); // If the timer expires, start an election
            }
        };
        
        // CHANGED: Schedule the new task
            timer.schedule(currentTimerTask, 
            rand.nextInt(ELECTION_TIMEOUT_MAX - ELECTION_TIMEOUT_MIN) + ELECTION_TIMEOUT_MIN);
        }

    /** Transitions this server to a CANDIDATE and starts an election. */
    private static synchronized void startElection() {
        if (currentState == State.LEADER) return; // Don't start if we are already leader

        currentState = State.CANDIDATE;
        currentTerm++; // Start a new term
        votedFor = myAddress; // Vote for ourself
        voteCount = 1; // We have one vote (from ourself)
        System.out.println("Election timer expired. Starting new election for term " + currentTerm);

        // Request votes from all peers
        broadcastToPeers("REQUEST_VOTE:" + currentTerm + ":" + myAddress);

        // Start a new timer. If this one expires, we start *another* election.
        resetElectionTimer();
    }

    /** Transitions this server to a LEADER. */
   private static synchronized void becomeLeader() {
        System.out.println("BECAME LEADER for term " + currentTerm);
        currentState = State.LEADER;
        currentLeaderAddress = myAddress;
        
        // NEW: Cancel any pending election timer
        if (currentTimerTask != null) {
            currentTimerTask.cancel();
        }
        
        // Start sending heartbeats
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (currentState == State.LEADER) {
                    broadcastToPeers("HEARTBEAT:" + currentTerm + ":" + myAddress);
                } else {
                    this.cancel(); // Stop sending if we are no longer leader
                }
            }
        }, 0, HEARTBEAT_INTERVAL); // Start immediately, then repeat
    }

    /** Transitions this server to a FOLLOWER. */
    private static synchronized void becomeFollower(int newTerm, String newLeader) {
        System.out.println("Becoming Follower in term " + newTerm);
        currentState = State.FOLLOWER;
        currentTerm = newTerm;
        votedFor = null;
        currentLeaderAddress = newLeader;
        resetElectionTimer(); // Start listening for heartbeats
    }
}
