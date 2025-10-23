import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap; // NEW: A thread-safe Map

public class WhiteboardServer 
{
    private static final Set<PrintWriter> clientWriters = Collections.synchronizedSet(new HashSet<>());
    private static final List<String> messageLog = Collections.synchronizedList(new ArrayList<>());

    // NEW: A map to hold connections TO other servers (Peers)
    // Key: "localhost:5002", Value: The writer to send messages to that server
    private static final Map<String, PrintWriter> peerWriters = new ConcurrentHashMap<>();
    
    // NEW: This server's own address, for identification
    private static String myAddress; 
    
    private static long sequenceNumber = 0;

    public static void main(String[] args) {
        // CHANGED: We now require 3 arguments
        if (args.length < 3) {
            System.out.println("Usage: java WhiteboardServer <my-port> <my-address> <peer-list>");
            System.out.println("Example: java WhiteboardServer 5001 localhost:5001 localhost:5002,localhost:5003");
            return;
        }

        int port = Integer.parseInt(args[0]);
        myAddress = args[1]; // e.g., "localhost:5001"
        String[] peerAddresses = args[2].split(","); // e.g., ["localhost:5002", "localhost:5003"]

        System.out.println("Whiteboard Server starting on " + myAddress + "...");

        // NEW: Start a "Connector" thread for each peer
        for (String peerAddr : peerAddresses) {
            if (!peerAddr.isBlank()) {
                Thread t = new Thread(new PeerConnector(peerAddr));
                t.setDaemon(true);
                t.start();
            }
        }

        // CHANGED: The main loop now handles both Client and Server handshakes
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket socket = serverSocket.accept();
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String handshake = in.readLine();
                    
                    if (handshake == null) {
                        throw new IOException("Empty handshake");
                    }

                    if ("IAM:CLIENT".equals(handshake)) {
                        System.out.println("Client connected: " + socket.getRemoteSocketAddress());
                        Thread t = new Thread(new ClientHandler(socket, in));
                        t.setDaemon(true);
                        t.start();
                    
                    // NEW: Handle connections from other servers
                    } else if (handshake.startsWith("IAM:SERVER:")) {
                        // The other server identifies itself, e.g., "IAM:SERVER:localhost:5002"
                        String peerAddr = handshake.split(":")[2];
                        System.out.println("Peer connected (inbound): " + peerAddr);
                        // This PeerHandler just listens for messages from that peer
                        Thread t = new Thread(new PeerHandler(socket, in, peerAddr));
                        t.setDaemon(true);
                        t.start();
                    
                    } else {
                        System.out.println("Unknown handshake: '" + handshake + "'. Closing: " + socket.getRemoteSocketAddress());
                        socket.close();
                    }
                } catch (IOException e) {
                    System.out.println("Handshake failed: " + e.getMessage());
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --- Client Handler (No changes from Step 1) ---
    private static class ClientHandler implements Runnable 
    {
        private final Socket socket;
        private PrintWriter out;
        private final BufferedReader in;

        ClientHandler(Socket socket, BufferedReader in) {
            this.socket = socket;
            this.in = in;
        }

        @Override
        public void run() {
            try {
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                
                synchronized(messageLog) {
                    for(String msg: messageLog) {
                        out.println(msg);
                    }
                }
                
                clientWriters.add(out);

                String line;
                while ((line = in.readLine()) != null) 
                {
                    // This logic will move in a future step, but is correct for now
                    synchronized (messageLog) {
                        sequenceNumber++;
                        messageLog.add(line);
                        System.out.println("Seq: "+ sequenceNumber +" Relayed: " + line);
                    }

                    synchronized (clientWriters) {
                        for (PrintWriter pw : clientWriters) {
                            pw.println(line);
                        }
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

    // --- NEW: PeerConnector Class ---
    // This class actively connects TO a peer and stores the connection
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
            // This loop retries forever to connect to its peer
            while (true) {
                try (Socket socket = new Socket(host, port)) {
                    System.out.println("Peer connected (outbound): " + peerAddress);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    
                    // Send our handshake to identify ourselves to the peer
                    out.println("IAM:SERVER:" + myAddress);
                    
                    // Store this writer so we can send messages TO this peer
                    peerWriters.put(peerAddress, out);

                    // Block and wait for the connection to die
                    // This is a simple way to keep the thread alive.
                    // If read() returns -1, the socket is closed.
                    socket.getInputStream().read(); 

                } catch (IOException e) {
                    // Connection failed, will retry after sleep
                    // System.out.println("Could not connect to peer " + peerAddress + ". Retrying...");
                } finally {
                    // Always remove the writer on disconnect so we don't send to a dead socket
                    peerWriters.remove(peerAddress);
                }

                try {
                    Thread.sleep(5000); // Wait 5s before retrying
                } catch (InterruptedException ignored) {}
            }
        }
    }

    // --- NEW: PeerHandler Class ---
    // This class handles an incoming connection FROM a peer
    private static class PeerHandler implements Runnable {
        private final Socket socket;
        private final BufferedReader in;
        private final String peerAddress;

        PeerHandler(Socket socket, BufferedReader in, String peerAddress) {
            this.socket = socket;
            this.in = in;
            this.peerAddress = peerAddress;
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    // For now, we just print peer messages.
                    // Soon, this will handle HEARTBEAT, REQUEST_VOTE, and DRAW commands.
                    System.out.println("Received from peer " + peerAddress + ": " + line);
                }
            } catch (IOException e) {
                System.out.println("Peer " + peerAddress + " disconnected: " + e.getMessage());
            } finally {
                try { if (in != null) in.close(); } catch (IOException ignored) {}
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}
