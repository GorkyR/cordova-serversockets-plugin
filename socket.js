const exec = require('cordova/exec')
const SOCKET_EVENT = 'SOCKET_EVENT'
const SERVICE_NAME = 'Sockets'

/*

const socket = new Socket()
socket.onOpen = () => {
	console.debug('connected FROM port:', socket.port)
}
socket.onData = (data) => {
	const text = String.fromCharCode(...data)
	console.debug('received:', text)
	socket.close()
}
socket.onClose = () => {
	console.debug('disconnected')
}
socket.connect('10.0.0.2', 4242, 
	() => {
		console.debug('connected')
		socket.write(bytes('Hello!'))
	},
	(error: string) => console.error('error:', error))

// ...

socket.shutdown()
socket.close()

*/

Socket.State = {}
Socket[(Socket.State.CLOSED = 0)] = 'CLOSED'
Socket[(Socket.State.OPENING = 1)] = 'OPENING'
Socket[(Socket.State.OPEN = 2)] = 'OPEN'
Socket[(Socket.State.CLOSING = 3)] = 'CLOSING'

function Socket() {
	this.id = Socket._guid()
	this._state = Socket.State.CLOSED
	this.local_port = null
	this.host = null
	this.port = null
	this.onOpen = null
	this.onData = null
	this.onClose = null
}

Socket.prototype.connect = function (host, port, success, error) {
	success ||= () => {}
	error ||= () => {}

	if (this.state != Socket.State.CLOSED) {
		console.error(`[ServerSocket] cannot open socket: socket is not closed`)
		error('not closed')
		return
	}

	this._port = null
	this._state = Socket.State.OPENING

	const _this = this

	function handler(event) {
		const payload = event.payload
		if (payload.id != _this.id) {
			return
		}
		switch (payload.type) {
			case 'connect':
				_this.local_port = payload.port
				_this.host = host
				_this.port = port
				_this.onOpen?.()
				break
			case 'data':
				_this.onData?.(payload.data)
				break
			case 'close':
				window.document.removeEventListener(SOCKET_EVENT, handler)
				_this._port = null
				_this._state = Socket.State.CLOSED
				_this.onClose?.()
				break
			default:
				console.error(`[SocketPlugin] unknown event type: ${payload.type}`)
		}
	}

	window.document.addEventListener(SOCKET_EVENT, handler)

	exec(
		() => {
			_this._state = Socket.State.OPEN
			success()
		},
		(e) => {
			window.document.removeEventListener(SOCKET_EVENT, handler)
			_this._state = Socket.State.CLOSED
			_this.onError?.(e)
			error(e)
		},
		SERVICE_NAME,
		'connect',
		[this.id, host, port]
	)
}

Socket.prototype.write = function (data, success, error) {
	success ||= () => {}
	error ||= () => {}
	exec(success, error, SERVICE_NAME, 'write', [this.id, Array.from(data)])
}

Socket.prototype.shutdown = function (success, error) {
	success ||= () => {}
	error ||= () => {}
	exec(success, error, SERVICE_NAME, 'shutdown', [this.id])
}

Socket.prototype.close = function (success, error) {
	success ||= () => {}
	error ||= () => {}
	if (this.state != Socket.State.OPEN) {
		console.error('[SocketPlugin] cannot close server socket: was not open')
		error('not open')
	} else {
		this._state = Socket.State.CLOSING
		exec(
			() => {
				this._port = null
				this._state = Socket.State.CLOSED
				success()
			},
			(e) => {
				this._state = Socket.State.OPEN
				error(e)
			},
			SERVICE_NAME,
			'close',
			[this.id]
		)
	}
}

Object.defineProperty(Socket.prototype, 'state', {
	get: function () {
		return this._state
	},
	enumerable: true,
	configurable: true,
})

Socket.dispatch = function (payload) {
	const event = window.document.createEvent('Events')
	event.initEvent(SOCKET_EVENT, true, true)
	event.payload = payload
	window.document.dispatchEvent(event)
}

Socket._guid = function () {
	function s4() {
		return Math.floor((1 + Math.random()) * 0x10000).toString(16)
	}
	return `${s4()}${s4()}-${s4()}-${s4()}-${s4()}${s4()}${s4()}`
}

module.exports = Socket
