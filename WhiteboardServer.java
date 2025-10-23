import java.io.*;
import java.net.*;
import java.util.*;

public class WhiteboardServer 
{
    // CHANGED: Renamed 'clients' to 'clientWriters' for clarity
    private static final Set<PrintWriter> clientWriters = Collections.synchronizedSet(new HashSet<>());
    
    // CHANGED: Made the log 'final' and synchronized on it directly
    private static final List<String> messageLog = Collections.synchronizedList(new ArrayList<>());
    
    // We don't need these helper methods anymore
    // public static void addToMessageLog(String message) ...
    // public static List<String> getMessageLog() ...

    private static long sequenceNumber = 0;

    public static void main(String[] args) {
        int port;
        if(args.length>0)
        {
            port = Integer.parseInt(args[0]);
        }
        else
        {  
            port=5001;
            System.out.println("No port specified. Using default port 5001.");
        }
        System.out.println("Whiteboard Server starting on port " + port + "...");
        
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket socket = serverSocket.accept();
                
                // NEW: Add handshake logic to identify connection type
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String handshake = in.readLine();
                    
                    if ("IAM:CLIENT".equals(handshake)) {
                        System.out.println("Client connected: " + socket.getRemoteSocketAddress());
                        // CHANGED: Pass the 'in' reader to the handler so the handshake line isn't lost
                        Thread t = new Thread(new ClientHandler(socket, in));
                        t.setDaemon(true);
                        t.start();
                    } else {
                        // In the future, we'll check for "IAM:SERVER" here
                        System.out.println("Unknown connection type. Closing: " + socket.getRemoteSocketAddress());
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

    // CHANGED: This is now the Client Handler
    private static class ClientHandler implements Runnable 
    {
        private final Socket socket;
        private PrintWriter out;
        private final BufferedReader in; // CHANGED: 'in' is now final and passed in constructor

        // CHANGED: Constructor now accepts the BufferedReader
        ClientHandler(Socket socket, BufferedReader in) {
            this.socket = socket;
            this.in = in; // It was created and used in main() for the handshake
        }

        @Override
        public void run() {
            try {
                // CHANGED: 'in' is already created
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                
                // Replay the message history to the newly connected client
                synchronized(messageLog)
                {
                    for(String msg: messageLog)
                    {
                        out.println(msg);
                    }
                }
                
                // Add this client's writer to the broadcast list
                clientWriters.add(out);

                String line;
                while ((line = in.readLine()) != null) 
                {
                    // --- BEGIN BUG FIX ---
                    // The logic is now split: log ONCE, then broadcast.
                    
                    // Step 1: Log the message. This happens only once.
                    synchronized (messageLog) {
                        sequenceNumber++;
                        messageLog.add(line);
                        System.out.println("Seq: "+ sequenceNumber +" Relayed: " + line);
                    }

                    // Step 2: Broadcast to all connected clients.
                    synchronized (clientWriters) {
                        for (PrintWriter pw : clientWriters) 
                        {
                            pw.println(line);
                        }
                    }
                    // --- END BUG FIX ---
                }
            } catch (IOException e) {
                System.out.println("Client disconnected: " + socket.getRemoteSocketAddress());
            } finally 
            {
                // CHANGED: Use the correct set name
                if (out != null) clientWriters.remove(out); 
                try { if (in != null) in.close(); } catch (IOException ignored) {}
                if (out != null) out.close();
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}
