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

public class WhiteboardClient {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5001;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        new WhiteboardClient().start(host, port);
    }

    private void start(String host, int port) throws Exception {
        Socket socket = new Socket(host, port);
        System.out.println("Connected to server " + host + ":" + port);

        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);

        UUID clientId = UUID.randomUUID();
        JFrame frame = new JFrame("Collaborative Whiteboard - " + clientId.toString().substring(0, 8));
        WhiteboardPanel panel = new WhiteboardPanel(out, clientId);

        // Reader thread to apply remote draw commands
        Thread reader = new Thread(() -> {
            String line;
            try {
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("DRAW ")) {
                        panel.applyRemoteDraw(line);
                    }
                }
            } catch (IOException ex) {
                System.out.println("Connection closed.");
            }
        });
        reader.setDaemon(true);
        reader.start();

        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(toolbar(panel), BorderLayout.NORTH);
        frame.add(panel, BorderLayout.CENTER);
        frame.setSize(1000, 700);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
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
        private final PrintWriter out;
        private final UUID clientId;
        private Point last;
        private Color drawColor = Color.BLACK;
        private float strokeWidth = 3f;

        WhiteboardPanel(PrintWriter out, UUID clientId) {
            this.out = out;
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
            String msg = String.format("DRAW %s %d %d %d %d %d %d %d %.2f",
                    clientId, a.x, a.y, b.x, b.y,
                    color.getRed(), color.getGreen(), color.getBlue(), stroke);
            out.println(msg);
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

// ================================================
// File: README (how to compile & run)
// ================================================
// 1) Save the two files above (WhiteboardServer.java and WhiteboardClient.java) in the same folder.
// 2) Compile:
//      javac WhiteboardServer.java WhiteboardClient.java
// 3) Run server:
//      java WhiteboardServer
// 4) Run multiple clients (same machine):
//      java WhiteboardClient
//    Or connect to remote host:
//      java WhiteboardClient <server-hostname-or-ip> 5001
//
// Notes:
// - This is a minimal demo using plain TCP sockets and Swing. It uses a text protocol for simplicity.
// - The server is multithreaded (one thread per client); the client also uses a separate thread to read updates.
// - The "Clear (local)" button only clears your local canvas (kept simple). You can extend the protocol with
//   a CLEAR message and have the server broadcast it to make global clears.
// - You can also extend with UNDO, shapes, images, or persistence by defining more message types.
