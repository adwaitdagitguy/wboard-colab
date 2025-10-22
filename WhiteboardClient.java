// ================================================
// File: WhiteboardClient.java
// Swing-based client that connects to the server and draws in real-time.
// Each drag emits short line segments to reduce message size and simplify rendering.
// ================================================

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Line2D;
import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.StringTokenizer;
import java.util.Arrays;

public class WhiteboardClient {
    private static final String DEFAULT_HOST = "localhost"; // basically the ip address
    private static final int DEFAULT_PORT = 5001;
    private static final List<Integer> PORTS_LIST= new ArrayList<>(Arrays.asList(5001, 5002, 5003));
    public static void main(String[] args) throws Exception 
    {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        // try to connect to specified port in the arguments passed , if fails try other ports from the list
        // start the client
        new WhiteboardClient().start(PORTS_LIST);
    }

    private void start(List<Integer> PORTS_LIST) throws Exception 
    {
        UUID clientId = UUID.randomUUID();
        JFrame frame = new JFrame("Collaborative Whiteboard - Client " + clientId);
        WhiteboardPanel panel = new WhiteboardPanel(clientId);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(toolbar(panel), BorderLayout.NORTH);
        frame.add(panel, BorderLayout.CENTER);
        frame.setSize(1000, 700);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        while(true)
        {
            Socket socket = null;
            for(int port: PORTS_LIST)
            {
                try
                {
                    String host = DEFAULT_HOST;
                    System.out.println("Trying to connect to server at " + host + ":" + port + "...");
                    socket = new Socket(host, port);
                    System.out.println("Connected to server at " + host + ":" + port + ".");
                    break;
                }
                catch(IOException e)
                {
                    System.out.println("Could not connect to port "+ port +", Trying next port. "+ e.getMessage());
                    socket = null;
                }
                catch(Exception e)
                {
                    System.out.println("Unexpected error: "+ e.getMessage());
                }
            }
            if(socket==null)
            {
                System.out.println("All connection attempts failed. Retrying in 5 seconds...");
                Thread.sleep(5000);
                continue;
            }
            // if connection successful, launch the whiteboard UI
            try
            {
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
                panel.setPrintWriter(out);
                frame.setTitle("Collaborative Whiteboard - Client " + clientId + " (Connected to " + socket.getRemoteSocketAddress() + ")");
                Thread reader = new Thread(() -> 
                {
                try {
                    String line;
                     while ((line = in.readLine()) != null) {
                        panel.applyRemoteDraw(line);
                        }
                    } 
                    catch (IOException ex) {// Connection lost
                        System.out.println("Connection lost: " + ex.getMessage());
                    }
                });
                reader.setDaemon(true);
                reader.start();
                reader.join();

            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
            finally
            {
                // -- Clean up on disconnect
                panel.setPrintWriter(null);
                try
                {
                    if(socket!=null)
                    {
                        socket.close();
                    }
                }
                catch(IOException ignored){}
                System.out.println("Disconnected, retrying connection...");
            }
            
    }
    }

    private JComponent toolbar(WhiteboardPanel panel) {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JLabel colorLabel = new JLabel("Color:");
        JButton colorBtn = new JButton("Pick");
        colorBtn.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(bar, "Choose Color", panel.getDrawColor());
            if (chosen != null) panel.setDrawColor(chosen);
        });

        JLabel strokeLabel = new JLabel("Stroke:");
        JSpinner strokeSpinner = new JSpinner(new SpinnerNumberModel(3.0, 1.0, 50.0, 1.0));
        strokeSpinner.addChangeListener(e -> panel.setStrokeWidth(((Double) strokeSpinner.getValue()).floatValue()));

        JButton clearBtn = new JButton("Clear (local)");
        clearBtn.addActionListener(e -> panel.clearLocal());

        bar.add(colorLabel);
        bar.add(colorBtn);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(strokeLabel);
        bar.add(strokeSpinner);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(clearBtn);
        return bar;
    }

    // ================= Whiteboard Panel =================
    static class WhiteboardPanel extends JPanel {
        private final List<DrawCommand> commands = new ArrayList<>();
        private volatile PrintWriter out;
        private final UUID clientId;
        private Point last;
        private Color drawColor = Color.BLACK;
        private float strokeWidth = 3f;

        WhiteboardPanel(UUID clientId) {
            this.clientId = clientId;
            setBackground(Color.WHITE);

            MouseAdapter adapter = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    last = e.getPoint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    Point cur = e.getPoint();
                    if (last != null) {
                        addLocalSegment(last, cur, drawColor, strokeWidth);
                        sendSegment(last, cur, drawColor, strokeWidth);
                    }
                    last = cur;
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    last = null;
                }
            };
            addMouseListener(adapter);
            addMouseMotionListener(adapter);
        }
        void setPrintWriter(PrintWriter out) {
            this.out = out;
        }
        void setDrawColor(Color c) { this.drawColor = c; }
        Color getDrawColor() { return drawColor; }
        void setStrokeWidth(float w) { this.strokeWidth = w; }

        void clearLocal() {
            commands.clear();
            repaint();
        }

        private void addLocalSegment(Point a, Point b, Color color, float stroke) {
            commands.add(new DrawCommand(a.x, a.y, b.x, b.y, color, stroke));
            repaint();
        }

        private void sendSegment(Point a, Point b, Color color, float stroke) {
            // Message format: DRAW <clientId> <x1> <y1> <x2> <y2> <r> <g> <b> <stroke>
            PrintWriter currentOut = this.out;
            if(currentOut!=null){
            String msg = String.format("DRAW %s %d %d %d %d %d %d %d %.2f",
                    clientId, a.x, a.y, b.x, b.y,
                    color.getRed(), color.getGreen(), color.getBlue(), stroke);
            out.println(msg);
            }
            else
            {
                // ADD buffering or notification can be implemented here
                System.out.println("OFFLINE.");
                // store all messages in a buffer to send later

            }
        }

        void applyRemoteDraw(String line) {
            try {
                // Tokenize and parse
                StringTokenizer st = new StringTokenizer(line);
                st.nextToken(); // DRAW
                String sender = st.nextToken();
                // Ignore our own echo to avoid double-drawing
                if (sender.equals(clientId.toString())) return;

                int x1 = Integer.parseInt(st.nextToken());
                int y1 = Integer.parseInt(st.nextToken());
                int x2 = Integer.parseInt(st.nextToken());
                int y2 = Integer.parseInt(st.nextToken());
                int r = Integer.parseInt(st.nextToken());
                int g = Integer.parseInt(st.nextToken());
                int b = Integer.parseInt(st.nextToken());
                float stroke = Float.parseFloat(st.nextToken());

                Color col = new Color(r, g, b);
                commands.add(new DrawCommand(x1, y1, x2, y2, col, stroke));
                SwingUtilities.invokeLater(this::repaint);
            } catch (Exception ignore) {
                // Malformed line; ignore
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (DrawCommand c : commands) {
                g2.setColor(c.color);
                g2.setStroke(new BasicStroke(c.stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new Line2D.Float(c.x1, c.y1, c.x2, c.y2));
            }
            g2.dispose();
        }
    }

    // Represents one line segment with color and stroke
    static class DrawCommand {
        final int x1, y1, x2, y2;
        final Color color;
        final float stroke;
        DrawCommand(int x1, int y1, int x2, int y2, Color color, float stroke) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.color = color; this.stroke = stroke;
        }
    }
}

