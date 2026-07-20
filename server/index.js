require('dotenv').config();
const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const { v4: uuidv4 } = require('uuid');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: '*', methods: ['GET', 'POST'] },
  pingTimeout: 60000,
  pingInterval: 25000,
  transports: ['websocket', 'polling']
});

app.use(cors());
app.use(express.json());

const PORT = process.env.PORT || 3000;
const SESSION_SECRET = process.env.SESSION_SECRET || 'touchmirror_default_secret';

// Store session rooms: sessionId -> { controller: socketId|null, targets: Set<socketId>, screenSize: {w,h} }
const rooms = new Map();

// ─── REST endpoints ──────────────────────────────────────────────────────────
app.get('/', (req, res) => {
  const stats = [];
  rooms.forEach((room, sessionId) => {
    stats.push({
      sessionId,
      hasController: !!room.controller,
      targetCount: room.targets.size,
      screenSize: room.screenSize
    });
  });
  res.json({ status: 'ok', version: '1.0.0', sessions: stats });
});

app.get('/health', (req, res) => res.json({ status: 'ok', uptime: process.uptime() }));

// ─── Socket.IO ────────────────────────────────────────────────────────────────
io.on('connection', (socket) => {
  console.log(`[+] Socket connected: ${socket.id}`);

  let currentRoom = null;
  let currentRole = null; // 'controller' | 'target'

  // Client sends { sessionId, role, secret, screenWidth, screenHeight }
  socket.on('join', (data) => {
    const { sessionId, role, secret, screenWidth, screenHeight } = data;

    if (!sessionId || !role || !secret) {
      socket.emit('error', { code: 'INVALID_JOIN', message: 'sessionId, role, secret required' });
      return;
    }
    if (secret !== SESSION_SECRET) {
      socket.emit('error', { code: 'WRONG_SECRET', message: 'Invalid session secret' });
      return;
    }
    if (role !== 'controller' && role !== 'target') {
      socket.emit('error', { code: 'INVALID_ROLE', message: 'role must be controller or target' });
      return;
    }

    // Init room if needed
    if (!rooms.has(sessionId)) {
      rooms.set(sessionId, { controller: null, targets: new Set(), screenSize: { w: 1080, h: 1920 } });
    }
    const room = rooms.get(sessionId);

    if (role === 'controller') {
      if (room.controller) {
        // Disconnect old controller
        io.to(room.controller).emit('kicked', { reason: 'New controller connected' });
        const oldSock = io.sockets.sockets.get(room.controller);
        if (oldSock) oldSock.leave(sessionId);
      }
      room.controller = socket.id;
      if (screenWidth && screenHeight) {
        room.screenSize = { w: screenWidth, h: screenHeight };
        // Broadcast new screen size to targets
        socket.to(sessionId).emit('screen_size', room.screenSize);
      }
    } else {
      room.targets.add(socket.id);
    }

    socket.join(sessionId);
    currentRoom = sessionId;
    currentRole = role;

    socket.emit('joined', {
      sessionId,
      role,
      socketId: socket.id,
      screenSize: room.screenSize,
      targetCount: room.targets.size,
      hasController: !!room.controller
    });

    // Notify room members
    socket.to(sessionId).emit('peer_update', {
      hasController: !!room.controller,
      targetCount: room.targets.size
    });

    console.log(`[*] ${role} joined session "${sessionId}" (targets: ${room.targets.size})`);
  });

  // Touch event from controller → broadcast to all targets in same room
  // Payload: { action, pointers: [{id, x, y}], timestamp }
  // x,y are normalized 0.0–1.0
  socket.on('touch', (data) => {
    if (!currentRoom || currentRole !== 'controller') return;
    const room = rooms.get(currentRoom);
    if (!room) return;
    // Relay to all targets
    socket.to(currentRoom).emit('touch', { ...data, ts: Date.now() });
  });

  // Batch of touch events for smoother mirroring
  socket.on('touch_batch', (batch) => {
    if (!currentRoom || currentRole !== 'controller') return;
    socket.to(currentRoom).emit('touch_batch', batch);
  });

  // Controller paused/resumed
  socket.on('mirror_state', (data) => {
    if (!currentRoom || currentRole !== 'controller') return;
    socket.to(currentRoom).emit('mirror_state', data);
  });

  // Target acknowledges (optional, for latency tracking)
  socket.on('ack', (data) => {
    if (currentRoom && currentRole === 'target') {
      const room = rooms.get(currentRoom);
      if (room && room.controller) {
        io.to(room.controller).emit('ack', { ...data, targetId: socket.id });
      }
    }
  });

  socket.on('disconnect', (reason) => {
    console.log(`[-] Socket disconnected: ${socket.id} (${reason})`);
    if (!currentRoom) return;
    const room = rooms.get(currentRoom);
    if (!room) return;

    if (currentRole === 'controller' && room.controller === socket.id) {
      room.controller = null;
      socket.to(currentRoom).emit('controller_disconnected', {});
      socket.to(currentRoom).emit('peer_update', { hasController: false, targetCount: room.targets.size });
    } else if (currentRole === 'target') {
      room.targets.delete(socket.id);
      socket.to(currentRoom).emit('peer_update', { hasController: !!room.controller, targetCount: room.targets.size });
    }

    // Clean up empty rooms
    if (!room.controller && room.targets.size === 0) {
      rooms.delete(currentRoom);
      console.log(`[*] Room "${currentRoom}" cleaned up`);
    }
  });
});

server.listen(PORT, () => {
  console.log(`TouchMirror server running on port ${PORT}`);
  console.log(`Session secret: ${SESSION_SECRET === 'touchmirror_default_secret' ? '[DEFAULT - change in .env]' : '[custom]'}`);
});
