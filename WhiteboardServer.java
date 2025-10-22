// The server does not interpret messages; it simply relays them to all clients.
// ================================================

import java.io.*;
import java.net.*;
import java.util.*;

public class WhiteboardServer 
{
    private static final int PORT = 5001;
    private static final Set<PrintWriter> clients = Collections.synchronizedSet(new HashSet<>());
    static List<String> messageLog = new ArrayList<>();
    public static void addToMessageLog(String message) {
        messageLog.add(message);
    }
    
    public static List<String> getMessageLog()
    {
        return messageLog;
    }
    private static long sequenceNumber = 0;
    // time to implement first clock synchronization
    public static void main(String[] args) {
        System.out.println("Whiteboard Server starting on port " + PORT + "...");
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Client connected: " + socket.getRemoteSocketAddress());
                Thread t = new Thread(new ClientHandler(socket));
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class ClientHandler implements Runnable 
    {
        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        // whenever the client joins for the first time , this thread is created
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                // replay the message history to the newly connected client
                synchronized(messageLog)
                {
                    for(String ms: getMessageLog())
                    {
                        out.println(ms);
                    }
                }
                
                // now start broadcasting new messages to all clients
                clients.add(out);

                String line;
                while ((line = in.readLine()) != null) 
                {
                    // Broadcast to all clients
                    synchronized (clients) {
                        for (PrintWriter pw : clients) 
                        {
                            sequenceNumber++;
                            WhiteboardServer.addToMessageLog(line);
                            pw.println(line);
                            System.out.println("Seq: "+ sequenceNumber +" Relayed: " + line);
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("Client disconnected: " + socket.getRemoteSocketAddress());
            } finally 
            {
                if (out != null) clients.remove(out);
                try { if (in != null) in.close(); } catch (IOException ignored) {}
                if (out != null) out.close();
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}
