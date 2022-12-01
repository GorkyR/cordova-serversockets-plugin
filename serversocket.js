const exec = require('cordova/exec')
const SERVER_SOCKET_EVENT = 'SERVER_SOCKET_EVENT'
const SERVICE_NAME = 'ServerSockets'

/*

const server = new ServerSocket()
server.onConnection = (socket) => {
	console.debug('socket id:',   socket.id)
	console.debug('socket host:', socket.host)
	console.debug('socket port:', socket.port)
	socket.onData = (data) => {
		const text = String.fromCharCodes(...data)
		console.debug(text)
	}
	socket.onClose = () => {
		console.debug('socket', socket.id, 'disconnected')
	}
	socket.write(bytes('some data'))
	socket.close()
}
server.onClose = () => {
	console.debug('stopped listening on port:', server.port);
}
server.listen(4242, 
	() => console.debug('listening on:', server.port), 
	(error: string) => { console.error('not listening. error:', error) })

// ...

server.close()

*/

ServerSocket.State = {}
ServerSocket[(ServerSocket.State.CLOSED = 0)] = 'CLOSED'
ServerSocket[(ServerSocket.State.OPENING = 1)] = 'OPENING'
ServerSocket[(ServerSocket.State.LISTENING = 2)] = 'LISTENING'
ServerSocket[(ServerSocket.State.CLOSING = 3)] = 'CLOSING'

ServerSocketClient.State = {}
ServerSocketClient.State[(ServerSocketClient.State.CLOSED = 0)] = 'CLOSED'
ServerSocketClient.State[(ServerSocketClient.State.OPEN = 2)] = 'OPEN'
ServerSocketClient.State[(ServerSocketClient.State.CLOSING = 3)] = 'CLOSING'

function ServerSocket() {
	this._port = null
	this._state = ServerSocket.State.CLOSED
	this.onConnection = null
	this.onClose = null
}

function ServerSocketClient(id, host, port) {
	this.id = id
	this.host = host
	this.port = port
	this._state = ServerSocketClient.State.OPEN
	this.onData = null
	this.onClose = null
}

ServerSocket.prototype.listen = function (port, success, error) {
	success ||= () => {}
	error ||= () => {}

	if (this.state != ServerSocket.State.CLOSED) {
		console.error(`[ServerSocket] cannot open server socket: socket is not closed`)
		error('not closed')
		return
	}

	this._port = null
	this._state = ServerSocket.State.OPENING

	const _this = this

	function handler(event) {
		const payload = event.payload
		if (payload.socket || payload.port !== port) {
			return
		}
		switch (payload.type) {
			case 'connection':
				const { id, host, port } = payload.data
				const socket = new ServerSocketClient(id, host, port)
				function socket_handler(event) {
					const payload = event.payload
					if (payload.socket != socket.id) {
						return
					}
					switch (payload.type) {
						case 'data':
							const data = payload.data
							socket.onData?.(data)
							break
						case 'close':
							socket._state = ServerSocketClient.State.CLOSED
							socket.onClose?.()
							socket.id = null
							window.document.removeEventListener(SERVER_SOCKET_EVENT, socket_handler)
							break
						default:
							console.error(`[ServerSocketPlugin] unknown event type: ${payload.type}, socket: ${socket.id}`)
					}
				}
				window.document.addEventListener(SERVER_SOCKET_EVENT, socket_handler)
				_this.onConnection?.(socket)
				break
			case 'close':
				window.document.removeEventListener(SERVER_SOCKET_EVENT, handler)
				_this._port = null
				_this._state = ServerSocket.State.CLOSED
				_this.onClose?.()
				break
			default:
				console.error(`[ServerSocketPlugin] unknown event type: ${payload.type}`)
		}
	}

	exec(
		() => {
			window.document.addEventListener(SERVER_SOCKET_EVENT, handler)
			_this._state = ServerSocket.State.LISTENING
			_this._port = port
			success()
		},
		(e) => {
			_this._state = ServerSocket.State.CLOSED
			_this.onError?.(e)
			error(e)
		},
		SERVICE_NAME,
		'listen',
		[port]
	)
}

ServerSocket.prototype.close = function (success, error) {
	success ||= () => {}
	error ||= () => {}
	if (this.state != ServerSocket.State.LISTENING) {
		console.error('[ServerSocketPlugin] cannot close server socket: was not listening')
		error('not listening')
	} else {
		this._state = ServerSocket.State.CLOSING
		exec(
			() => {
				this._port = null
				this._state = ServerSocket.State.CLOSED
				success()
			},
			(e) => {
				this._state = ServerSocket.State.LISTENING
				error(e)
			},
			SERVICE_NAME,
			'close',
			[this.port]
		)
	}
}

Object.defineProperty(ServerSocket.prototype, 'port', {
	get: function () {
		return this._port
	},
	enumerable: true,
	configurable: true,
})

Object.defineProperty(ServerSocket.prototype, 'state', {
	get: function () {
		return this._state
	},
	enumerable: true,
	configurable: true,
})

ServerSocketClient.prototype.write = function (data, success, error) {
	success ||= () => {}
	error ||= () => {}
	exec(success, error, SERVICE_NAME, 'write', [this.id, Array.from(data)])
}

ServerSocketClient.prototype.close = function (success, error) {
	success ||= () => {}
	error ||= () => {}
	if (this.state != ServerSocketClient.State.OPEN) {
		console.error(`[ServerSocketPlugin] cannot close connection: already closed`)
		error('already closed')
	} else {
		this._state = ServerSocketClient.State.CLOSING
		exec(
			() => {
				this._state = ServerSocketClient.State.CLOSED
				success()
			},
			(e) => {
				this._state = ServerSocketClient.State.OPEN
				error(e)
			},
			SERVICE_NAME,
			'close-client',
			[this.id]
		)
	}
}

Object.defineProperty(ServerSocketClient.prototype, 'state', {
	get: function () {
		return this._state
	},
	enumerable: true,
	configurable: true,
})

ServerSocket.dispatch = function (payload) {
	const event = window.document.createEvent('Events')
	event.initEvent(SERVER_SOCKET_EVENT, true, true)
	event.payload = payload
	window.document.dispatchEvent(event)
}

module.exports = ServerSocket
