package dev.gorky.cordovaserversocket;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;

public class ServerSocketPlugin extends CordovaPlugin {
	private ExecutorService executor;

	public ServerSocketPlugin() {
		this.executor = Executors.newSingleThreadExecutor();
	}

	@Override
	public boolean execute(String action, CordovaArgs args, CallbackContext callbacks) throws JSONException {
		if ("listen".equals(action)) {
			this.listen(args, callbacks);
		} else if ("close".equals(action)) {
			this.close(args, callbacks);
		} else if ("write".equals(action)) {
			this.write(args, callbacks);
		} else if ("shutdown".equals(action)) {
			this.shutdown(args, callbacks);
		} else if ("close-client".equals(action)) {
			this.close_client(args, callbacks);
		} else {
			callbacks.error(String.format("[ServerSocketPlugin] invalid action: %s", action));
			return false;
		}
		return true;
	}

	private Map<Integer, ServerSocket> servers = new HashMap<Integer, ServerSocket>();
	private Map<Integer, List<Socket>> server_sockets = new HashMap<Integer, List<Socket>>();
	private Map<String, Socket> sockets = new HashMap<String, Socket>();

	private void listen(CordovaArgs args, CallbackContext callbacks) throws JSONException {
		try {
			int port = args.getInt(0);

			if (servers.containsKey(port)) {
				ServerSocket server = servers.get(port);
				servers.remove(port);
				
				for (Socket socket : server_sockets.get(port)) {
					try { socket.close(); } finally {}
				}
				server_sockets.remove(port);

				try { server.close(); } finally {}

				dispatchClosed(port);
			}

			ServerSocket server = new ServerSocket(port);
			this.servers.put(port, server);

			List<Socket> server_sockets = new ArrayList<Socket>();
			this.server_sockets.put(port, server_sockets);

			this.executor.submit(new Runnable() {
				private ExecutorService _executor = Executors.newSingleThreadExecutor();

				@Override
				public void run() {
					try {
						while (true) {
							Socket socket = server.accept();
							server_sockets.add(socket);

							String id = guid();
							sockets.put(id, socket);

							dispatchConnected(port, id, socket.getInetAddress().toString(), socket.getPort());

							_executor.submit(new Runnable() {
								@Override
								public void run() {
									byte[] buffer = new byte[16 * 1024];
									int count = 0;
									try {
										while ((count = socket.getInputStream().read(buffer)) >= 0) {
											byte[] data = buffer.length == count? buffer : Arrays.copyOfRange(buffer, 0, count);
											dispatchData(id, bytes(data));
										}

										try {
											socket.close();
											server_sockets.remove(server_sockets.indexOf(socket));
										} finally {}

										dispatchClosed(id);
									} catch (Exception e) {
										callbacks.error(e.toString());
									}
								}

								private List<Byte> bytes(byte[] buffer) {
									List<Byte> bytes = new ArrayList<Byte>(buffer.length);
									for (int i = 0; i < buffer.length; i++) {
										bytes.add(buffer[i]);
									}
									return bytes;
								}
							});
						}
					} catch (Exception e) {
						callbacks.error(e.toString());
					}
				}
			});

			callbacks.success();
		} catch (Exception e) {
			callbacks.error(e.toString());
		}

	}

	private void close(CordovaArgs args, CallbackContext callbacks) throws JSONException {
		int port = args.getInt(0);
		if (servers.containsKey(port)) {
			try {
				ServerSocket server = servers.get(port);
				servers.remove(port);
				
				for (Socket client : server_sockets.get(port)) {
					try { client.close(); } finally {}
				}
				server_sockets.remove(port);

				try { server.close(); } finally {}

				dispatchClosed(port);

				callbacks.success();
			} catch (Exception e) {
				callbacks.error(e.toString());
			}
		} else {
			callbacks.error("[ServerSocketPlugin] Server socket was not open.");
		}
	}

	private void write(CordovaArgs args, CallbackContext callbacks) throws JSONException {
		String id = args.getString(0);
		try {
			JSONArray data = args.getJSONArray(1);

			byte[] buffer = new byte[data.length()];
			for (int i = 0; i < buffer.length; i++)
				buffer[i] = (byte)data.getInt(i);
			
			Socket socket = this.sockets.get(id);

			socket.getOutputStream().write(buffer);
			callbacks.success();
		} catch(Exception e) {
			callbacks.error(e.toString());
		}
	}

	private void shutdown(CordovaArgs args, CallbackContext callbacks) throws JSONException {
		String id = args.getString(0);
		Socket socket = this.sockets.get(id);
		try {
			socket.shutdownOutput();
			callbacks.success();
		} catch (Exception e) {
			callbacks.error(e.toString());
		}
	}

	private void close_client(CordovaArgs args, CallbackContext callbacks) throws JSONException {
		String id = args.getString(0);
		Socket socket = this.sockets.get(id);
		try {
			List<Socket> server_sockets = this.server_sockets.get(socket.getPort());
			server_sockets.remove( server_sockets.indexOf(socket) );
			socket.close();

			dispatchClosed(id);

			callbacks.success();
		} catch (Exception e) {
			callbacks.error(e.toString());
		}
	}

	private void dispatchConnected(int port, String id, String host, int host_port) throws JSONException {
		JSONObject payload = new JSONObject();
		payload.put("type", "connection");
		payload.put("port", port);
		JSONObject data = new JSONObject();
		data.put("id", id);
		data.put("host", host);
		data.put("port", host_port);
		payload.put("data", data);
		dispatch(payload);
	}

	private void dispatchClosed(int port) throws JSONException {
		JSONObject payload = new JSONObject();
		payload.put("type", "close");
		payload.put("port", port);
		dispatch(payload);
	}

	private void dispatchClosed(String id) throws JSONException {
		JSONObject payload = new JSONObject();
		payload.put("type", "close");
		payload.put("socket", id);
		dispatch(payload);
	}

	private void dispatchData(String id, List<Byte> data) throws JSONException {
		JSONObject payload = new JSONObject();
		payload.put("type", "data");
		payload.put("data", new JSONArray(data));
		payload.put("socket", id);
		dispatch(payload);
	}

	private void dispatch(JSONObject payload) {
		this.webView.sendJavascript(String.format("window.ServerSocket.dispatch(%s);", payload.toString()));
	}

	private String guid() {
		return UUID.randomUUID().toString();
	}
}