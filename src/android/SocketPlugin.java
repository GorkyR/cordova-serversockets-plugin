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

public class SocketPlugin extends CordovaPlugin {
	private ExecutorService executor;

	public SocketPlugin() {
		this.executor = Executors.newSingleThreadExecutor();
	}

	@Override
	public boolean execute(String action, CordovaArgs args, CallbackContext callbacks) throws JSONException {
		if ("connect".equals(action)) {
			this.connect(args, callbacks);
		} else if ("write".equals(action)) {
			this.write(args, callbacks);
		} else if ("shutdown".equals(action)) {
			this.shutdown(args, callbacks);
		} else if ("close".equals(action)) {
			this.close(args, callbacks);
		} else {
			callbacks.error(String.format("[SocketPlugin] invalid action: %s", action));
			return false;
		}
		return true;
	}

	private Map<String, Socket> sockets = new HashMap<String, Socket>();

	private void connect(CordovaArgs args, CallbackContext callbacks) throws JSONException {
		try {
			String id = args.getString(0);
			String host = args.getString(1);
			int port = args.getInt(2);

			Socket socket = new Socket(host, port);
			sockets.put(id, socket);
			dispatchConnected(id, socket.getLocalPort());

			executor.submit(new Runnable() {
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
							sockets.remove(id);
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

			callbacks.success();
		} catch (Exception e) {
			callbacks.error(e.toString());
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
	
	private void close(CordovaArgs args, CallbackContext callbacks) throws JSONException {
		String id = args.getString(0);

		if (this.sockets.containsKey(id)) {
			try {
				Socket socket = this.sockets.get(id);
				try {
					socket.close();
					this.sockets.remove(id);
				} finally {}

				dispatchClosed(id);

				callbacks.success();
			} catch (Exception e) {
				callbacks.error(e.toString());
			}
		} else {
			callbacks.error("[SocketPlugin] Socket was not open.");
		}
	}

	private void dispatchConnected(String id, int port) throws JSONException {
		JSONObject payload = new JSONObject();
		payload.put("type", "connect");
		payload.put("id", id);
		payload.put("port", port);
		dispatch(payload);
	}

	private void dispatchClosed(String id) throws JSONException {
		JSONObject payload = new JSONObject();
		payload.put("type", "close");
		payload.put("id", id);
		dispatch(payload);
	}

	private void dispatchData(String id, List<Byte> data) throws JSONException {
		JSONObject payload = new JSONObject();
		payload.put("type", "data");
		payload.put("id", id);
		payload.put("data", new JSONArray(data));
		dispatch(payload);
	}

	private void dispatch(JSONObject payload) {
		this.webView.sendJavascript(String.format("window.Socket.dispatch(%s);", payload.toString()));
	}
}