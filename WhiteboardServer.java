import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Timer;
import java.util.TimerTask;

public class WhiteboardServer 
{
    private static final Set<PrintWriter> clientWriters = Collections.synchronizedSet(new HashSet<>());
    private static final List<String> messageLog = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, PrintWriter> peerWriters = new ConcurrentHashMap<>();
    
    // --- Election State Variables ---
    private static String myAddress;
    private static volatile String currentLeaderAddress = null; // volatile: visible to all threads
    private static int totalServers = 0;
    private static int majority = 0;

    private static enum State { FOLLOWER, CANDIDATE, LEADER }
    private static volatile State currentState = State.FOLLOWER;
    private static volatile int currentTerm = 0; 
    private static volatile String votedFor = null;
    private static volatile int voteCount = 0;

    private static final Timer timer = new Timer();
    private static volatile TimerTask currentTimerTask; // To prevent timer bug
    private static final Random rand = new Random();
    private static final int ELECTION_TIMEOUT_MIN = 5000;
    private static final int ELECTION_TIMEOUT_MAX = 10000;
    private static final int HEARTBEAT_INTERVAL = 2000;

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

        totalServers = peerAddresses.length + 1;
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
                        // Fix for address parsing bug
                        String[] parts = handshake.split(":");
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

    // --- CHANGED: Client Handler (Now routes commands) ---
    private static class ClientHandler implements Runnable 
    {
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
                
                // Replay history
                synchronized(messageLog) {
                    for(String msg: messageLog) out.println(msg);
                }
                
                // Add *after* history replay
                clientWriters.add(out);
                
                String line;
                while ((line = in.readLine()) != null) {
                    
                    // --- BEGIN CONSISTENCY LOGIC ---
                    if (currentState == State.LEADER) {
                        // We are the Leader. This is the official order.
                        // 1. Log it
                        logAndRelay(line);
                        
                    } else {
                        // We are a Follower. Forward to the leader.
                        if (currentLeaderAddress != null) {
                            PrintWriter leaderWriter = peerWriters.get(currentLeaderAddress);
                            if (leaderWriter != null) {
                                // Forward the *exact* DRAW line to the leader
                                leaderWriter.println(line);
                            }
                        } else {
                            // No leader known, drop the message
                            System.out.println("Follower: No leader. Dropping: " + line);
                        }
                    }
                    // --- END CONSISTENCY LOGIC ---
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

    // --- Peer Connector (Unchanged from Step 3) ---
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
                } finally {
                    peerWriters.remove(peerAddress);
                }
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
    }

    // --- Peer Handler (Unchanged from Step 3) ---
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

    // --- Election Logic (Unchanged from Step 3 + Timer Fix) ---

    private static synchronized void handleClusterMessage(String line, String fromAddress) {
        
        // --- NEW: Handle replicated DRAW commands ---
        // A DRAW command looks like "DRAW <uuid> ...", it does not have colons
        if (line.startsWith("DRAW ")) {
            // This is a replicated draw command FROM THE LEADER.
            
            // 1. Log it to stay in sync
            synchronized (messageLog) {
                sequenceNumber++;
                messageLog.add(line);
                System.out.println("Replicating: " + line);
            }
            
            // 2. Broadcast to our local clients
            broadcastToLocalClients(line);
            return; // Done
        }
        // --- End of new logic ---

        // If not DRAW, it must be an election message
        try {
            String[] parts = line.split(":");
            String command = parts[0];
            int term = Integer.parseInt(parts[1]);
            // Fix for address parsing bug
            String senderAddress = parts[2] + ":" + parts[3]; 

            if (term > currentTerm) {
                becomeFollower(term, null);
            }

            switch (command) {
                case "HEARTBEAT":
                    if (term == currentTerm && currentState != State.LEADER) {
                        currentLeaderAddress = senderAddress;
                        // System.out.println("Heartbeat received from Leader " + senderAddress);
                        resetElectionTimer();
                    }
                    break;
                
                case "REQUEST_VOTE":
                    if (term == currentTerm && (votedFor == null || votedFor.equals(senderAddress))) {
                        votedFor = senderAddress;
                        System.out.println("Voting for " + senderAddress + " in term " + term);
                        PrintWriter pw = peerWriters.get(senderAddress);
                        if (pw != null) {
                            pw.println("VOTE_GRANTED:" + currentTerm + ":" + myAddress);
                        }
                        resetElectionTimer();
                    }
                    break;

                case "VOTE_GRANTED":
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

    private static void broadcastToPeers(String message) {
        for (PrintWriter pw : peerWriters.values()) {
            pw.println(message);
        }
    }

    // --- NEW: Helper function to log and relay ---
    /** This function is called ONLY by the LEADER to finalize a command. */
    private static void logAndRelay(String message) {
        // 1. Log it (sets the official order)
        synchronized (messageLog) {
            sequenceNumber++;
            messageLog.add(message);
            System.out.println("Seq: "+ sequenceNumber +" Relayed: " + message);
        }
        
        // 2. Broadcast to local clients
        broadcastToLocalClients(message);
        
        // 3. Replicate to all peers
        broadcastToPeers(message);
    }
    
    // --- NEW: Helper function to broadcast to clients ---
    /** Broadcasts a message to all locally connected clients. */
    private static void broadcastToLocalClients(String message) {
        synchronized (clientWriters) {
            for (PrintWriter pw : clientWriters) {
                pw.println(message);
            }
        }
    }

    // --- Timer logic with fix ---
    private static synchronized void resetElectionTimer() {
        if (currentTimerTask != null) {
            currentTimerTask.cancel();
        }
        currentTimerTask = new TimerTask() {
            @Override
            public void run() {
                startElection();
            }
        };
        timer.schedule(currentTimerTask, 
            rand.nextInt(ELECTION_TIMEOUT_MAX - ELECTION_TIMEOUT_MIN) + ELECTION_TIMEOUT_MIN);
    }

    private static synchronized void startElection() {
        if (currentState == State.LEADER) return;
        currentState = State.CANDIDATE;
        currentTerm++;
        votedFor = myAddress;
        voteCount = 1;
        System.out.println("Election timer expired. Starting new election for term " + currentTerm);
        broadcastToPeers("REQUEST_VOTE:" + currentTerm + ":" + myAddress);
        resetElectionTimer();
    }

    private static synchronized void becomeLeader() {
        if (currentState == State.LEADER) return; // Fix: Don't re-become leader
        
        System.out.println("BECAME LEADER for term " + currentTerm);
        currentState = State.LEADER;
        currentLeaderAddress = myAddress;
        
        if (currentTimerTask != null) {
            currentTimerTask.cancel();
        }
        
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (currentState == State.LEADER) {
                    broadcastToPeers("HEARTBEAT:" + currentTerm + ":" + myAddress);
                } else {
                    this.cancel();
                }
            }
        }, 0, HEARTBEAT_INTERVAL);
    }

    private static synchronized void becomeFollower(int newTerm, String newLeader) {
        System.out.println("Becoming Follower in term " + newTerm);
        currentState = State.FOLLOWER;
        currentTerm = newTerm;
        votedFor = null;
        currentLeaderAddress = newLeader;
        resetElectionTimer();
    }
}
