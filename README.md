# wboard-colab
================================================
File: README (how to compile & run)
================================================
1) Save the two files above (WhiteboardServer.java and WhiteboardClient.java) in the same folder.
2) Compile

Notes:
This is a minimal demo using plain TCP sockets and Swing. It uses a text protocol for simplicity.
The server is multithreaded (one thread per client); the client also uses a separate thread to read updates.
The "Clear (local)" button only clears your local canvas (kept simple). You can extend the protocol with
CLEAR message and have the server broadcast it to make global clears.
You can also extend with UNDO, shapes, images, or persistence by defining more message types.
