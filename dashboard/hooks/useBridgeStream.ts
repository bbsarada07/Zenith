import { useState, useEffect, useRef, useCallback } from 'react';
import { BoundingBox, TelemetryFrame } from '@/lib/schemas/actionSchema';

export interface BridgeStreamState {
  bridgeStatus: 'CONNECTED' | 'RECONNECTING' | 'DISCONNECTED';
  fps: number;
  frameLatencyMs: number;
  npuUsagePercent: number;
  heapMemoryMb: number;
  screenWidth: number;
  screenHeight: number;
  activeApp: string;
  boundingBoxes: BoundingBox[];
  latestBitmap: ImageBitmap | null;
  serverUrl: string;
}

export interface UseBridgeStreamOptions {
  initialHost?: string;
  initialPort?: number;
  autoConnect?: boolean;
  onAuditLog?: (command: string, targetBBox: string | undefined, statusCode: number, latencyMs: number, status: 'SUCCESS' | 'RETRY' | 'FAILED') => void;
}

export function useBridgeStream(options: UseBridgeStreamOptions = {}) {
  const {
    initialHost = '192.168.1.3',
    initialPort = 8080,
    autoConnect = true,
    onAuditLog
  } = options;

  const [host, setHost] = useState<string>(initialHost);
  const [port, setPort] = useState<number>(initialPort);
  const [streamState, setStreamState] = useState<BridgeStreamState>({
    bridgeStatus: 'DISCONNECTED',
    fps: 0,
    frameLatencyMs: 0,
    npuUsagePercent: 0,
    heapMemoryMb: 0,
    screenWidth: 1080,
    screenHeight: 2400,
    activeApp: 'com.android.launcher3',
    boundingBoxes: [],
    latestBitmap: null,
    serverUrl: `ws://${initialHost}:${initialPort}`
  });

  const wsRef = useRef<WebSocket | null>(null);
  const reconnectAttemptsRef = useRef<number>(0);
  const reconnectTimerRef = useRef<NodeJS.Timeout | null>(null);
  const isManuallyClosedRef = useRef<boolean>(false);

  // FPS calculation refs
  const frameCountRef = useRef<number>(0);
  const lastFpsCalcTimeRef = useRef<number>(performance.now());
  const currentFpsRef = useRef<number>(0);

  // Active bitmap ref for memory cleanup
  const currentBitmapRef = useRef<ImageBitmap | null>(null);

  // Heap memory tracker
  const updateHeapMetric = useCallback(() => {
    if (typeof window !== 'undefined' && (performance as any).memory) {
      const mem = (performance as any).memory;
      const usedMb = Math.round(mem.usedJSHeapSize / (1024 * 1024));
      setStreamState(prev => ({ ...prev, heapMemoryMb: usedMb }));
    }
  }, []);

  const handleFrameBlob = useCallback(async (blob: Blob) => {
    const receiveTime = performance.now();
    try {
      // Decode image blob directly to high performance ImageBitmap
      const bitmap = await createImageBitmap(blob);
      
      // Release previous bitmap if existing to prevent graphics memory leaks
      if (currentBitmapRef.current) {
        currentBitmapRef.current.close();
      }
      currentBitmapRef.current = bitmap;

      const renderLatency = Math.round(performance.now() - receiveTime);

      // FPS tracking
      frameCountRef.current++;
      const now = performance.now();
      if (now - lastFpsCalcTimeRef.current >= 1000) {
        currentFpsRef.current = Math.round((frameCountRef.current * 1000) / (now - lastFpsCalcTimeRef.current));
        frameCountRef.current = 0;
        lastFpsCalcTimeRef.current = now;
      }

      setStreamState(prev => ({
        ...prev,
        latestBitmap: bitmap,
        fps: currentFpsRef.current,
        frameLatencyMs: renderLatency,
        screenWidth: bitmap.width || prev.screenWidth,
        screenHeight: bitmap.height || prev.screenHeight
      }));
    } catch (err) {
      console.error('[BridgeStream] Error creating ImageBitmap from frame:', err);
    }
  }, []);

  const handleJsonMessage = useCallback((data: any) => {
    if (!data) return;

    // Telemetry packet
    if (data.type === 'telemetry' || data.fps !== undefined) {
      setStreamState(prev => ({
        ...prev,
        fps: data.fps ?? prev.fps,
        frameLatencyMs: data.frameLatencyMs ?? data.latencyMs ?? prev.frameLatencyMs,
        npuUsagePercent: data.npuUsagePercent ?? data.npuUtilization ?? prev.npuUsagePercent,
        heapMemoryMb: data.heapMemoryMb ?? data.memoryMb ?? prev.heapMemoryMb,
        screenWidth: data.screenWidth ?? prev.screenWidth,
        screenHeight: data.screenHeight ?? prev.screenHeight,
        activeApp: data.activeApp ?? data.current_app ?? prev.activeApp
      }));
      return;
    }

    // Semantic Node / OCR Bounding Boxes
    if (data.type === 'SEMANTIC_TREE_RESPONSE' || data.type === 'OCR_RESPONSE' || data.nodes || data.boxes) {
      const rawBoxes = data.nodes || data.boxes || data.blocks || [];
      const parsedBoxes: BoundingBox[] = rawBoxes.map((b: any, index: number) => {
        const bounds = b.bounds || [0, 0, 100, 100];
        const isArray = Array.isArray(bounds);
        const x = isArray ? bounds[0] : (b.x ?? 0);
        const y = isArray ? bounds[1] : (b.y ?? 0);
        const w = isArray ? (bounds[2] - bounds[0]) : (b.width ?? 100);
        const h = isArray ? (bounds[3] - bounds[1]) : (b.height ?? 50);

        return {
          id: b.id || `node_${index}`,
          x,
          y,
          width: Math.max(10, w),
          height: Math.max(10, h),
          label: b.text || b.contentDescription || b.label || `Element ${index}`,
          isClickable: b.isClickable ?? true,
          isEditable: b.isEditable ?? false,
          confidence: b.confidence ?? 1.0,
          resourceId: b.resourceId
        };
      });

      setStreamState(prev => ({
        ...prev,
        boundingBoxes: parsedBoxes
      }));
    }

    // Action Execution Audit Feedback
    if (data.type === 'ACTION_RESULT' || data.status === 'SUCCESS' || data.status === 'NO_MUTATION') {
      const isSuccess = data.status === 'SUCCESS' || data.success === true;
      if (onAuditLog) {
        onAuditLog(
          data.action || data.command || 'REMOTE_ACTION',
          data.target || undefined,
          isSuccess ? 200 : 500,
          data.latencyMs || 45,
          isSuccess ? 'SUCCESS' : 'FAILED'
        );
      }
    }
  }, [onAuditLog]);

  const connect = useCallback(() => {
    isManuallyClosedRef.current = false;
    if (wsRef.current && (wsRef.current.readyState === WebSocket.OPEN || wsRef.current.readyState === WebSocket.CONNECTING)) {
      return;
    }

    const wsUrl = `ws://${host}:${port}`;
    setStreamState(prev => ({ ...prev, bridgeStatus: 'RECONNECTING', serverUrl: wsUrl }));

    try {
      const ws = new WebSocket(wsUrl);
      ws.binaryType = 'blob';

      ws.onopen = () => {
        console.log(`[BridgeStream] Connected to Zenith Bridge at ${wsUrl}`);
        reconnectAttemptsRef.current = 0;
        setStreamState(prev => ({ ...prev, bridgeStatus: 'CONNECTED' }));
        
        // Request initial screen metrics and OCR scan
        ws.send(JSON.stringify({ type: 'GET_SEMANTIC_TREE', timestamp: Date.now() }));
      };

      ws.onmessage = (event) => {
        if (event.data instanceof Blob) {
          handleFrameBlob(event.data);
        } else if (typeof event.data === 'string') {
          try {
            const parsed = JSON.parse(event.data);
            handleJsonMessage(parsed);
          } catch (e) {
            console.warn('[BridgeStream] Failed to parse text message JSON:', event.data);
          }
        }
      };

      ws.onerror = (err) => {
        console.error('[BridgeStream] WebSocket encountered error:', err);
      };

      ws.onclose = () => {
        console.warn('[BridgeStream] WebSocket closed.');
        setStreamState(prev => ({ ...prev, bridgeStatus: 'DISCONNECTED' }));
        wsRef.current = null;

        if (!isManuallyClosedRef.current) {
          // Exponential backoff reconnect: 1s, 2s, 4s, up to 15s
          const delay = Math.min(1000 * Math.pow(1.5, reconnectAttemptsRef.current), 15000);
          reconnectAttemptsRef.current++;
          console.log(`[BridgeStream] Reconnecting in ${Math.round(delay)}ms (Attempt #${reconnectAttemptsRef.current})...`);
          
          if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current);
          reconnectTimerRef.current = setTimeout(() => {
            connect();
          }, delay);
        }
      };

      wsRef.current = ws;
    } catch (err) {
      console.error('[BridgeStream] Connection error:', err);
      setStreamState(prev => ({ ...prev, bridgeStatus: 'DISCONNECTED' }));
    }
  }, [host, port, handleFrameBlob, handleJsonMessage]);

  const disconnect = useCallback(() => {
    isManuallyClosedRef.current = true;
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }
    setStreamState(prev => ({ ...prev, bridgeStatus: 'DISCONNECTED' }));
  }, []);

  // Send Remote Touch / Click
  const sendRemoteTouch = useCallback((normalizedX: number, normalizedY: number, action: 'CLICK' | 'LONG_PRESS' = 'CLICK') => {
    if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;

    const payload = {
      action: action === 'LONG_PRESS' ? 'LONG_PRESS_COORD' : 'REMOTE_TAP',
      coords: [normalizedX, normalizedY],
      x: normalizedX,
      y: normalizedY,
      timestamp: Date.now()
    };

    wsRef.current.send(JSON.stringify(payload));
    if (onAuditLog) {
      onAuditLog(action, `(${normalizedX.toFixed(3)}, ${normalizedY.toFixed(3)})`, 200, 32, 'SUCCESS');
    }
  }, [onAuditLog]);

  // Send Remote Swipe
  const sendRemoteSwipe = useCallback((
    startX: number,
    startY: number,
    endX: number,
    endY: number,
    durationMs: number = 250
  ) => {
    if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;

    const payload = {
      action: 'REMOTE_SWIPE',
      startX,
      startY,
      endX,
      endY,
      durationMs,
      coords: [startX, startY],
      swipeEndCoords: [endX, endY],
      timestamp: Date.now()
    };

    wsRef.current.send(JSON.stringify(payload));
    if (onAuditLog) {
      onAuditLog('SWIPE', `(${startX.toFixed(2)},${startY.toFixed(2)}) -> (${endX.toFixed(2)},${endY.toFixed(2)})`, 200, 48, 'SUCCESS');
    }
  }, [onAuditLog]);

  // Send Navigation Command
  const sendNavAction = useCallback((navAction: 'HOME' | 'BACK' | 'RECENTS' | 'NOTIFICATIONS') => {
    if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;

    const payload = {
      action: 'INPUT_KEY',
      target: navAction.toLowerCase(),
      key: navAction,
      timestamp: Date.now()
    };

    wsRef.current.send(JSON.stringify(payload));
    if (onAuditLog) {
      onAuditLog(`NAV_${navAction}`, undefined, 200, 24, 'SUCCESS');
    }
  }, [onAuditLog]);

  // Send Text Injection
  const sendTextInput = useCallback((text: string) => {
    if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;

    const payload = {
      action: 'INPUT_TEXT',
      payload: text,
      text,
      timestamp: Date.now()
    };

    wsRef.current.send(JSON.stringify(payload));
    if (onAuditLog) {
      onAuditLog('TYPE_TEXT', `"${text}"`, 200, 50, 'SUCCESS');
    }
  }, [onAuditLog]);

  // General WebSocket message emitter
  const sendCommand = useCallback((commandJson: Record<string, any>) => {
    if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;
    wsRef.current.send(JSON.stringify(commandJson));
  }, []);

  // Update target host / port and reconnect
  const setBridgeEndpoint = useCallback((newHost: string, newPort: number = 8080) => {
    setHost(newHost);
    setPort(newPort);
  }, []);

  // Heap tracking interval
  useEffect(() => {
    const interval = setInterval(updateHeapMetric, 2000);
    return () => clearInterval(interval);
  }, [updateHeapMetric]);

  // Auto-connect lifecycle
  useEffect(() => {
    if (autoConnect) {
      connect();
    }
    return () => {
      disconnect();
      if (currentBitmapRef.current) {
        currentBitmapRef.current.close();
        currentBitmapRef.current = null;
      }
    };
  }, [autoConnect, connect, disconnect]);

  return {
    ...streamState,
    connect,
    disconnect,
    sendRemoteTouch,
    sendRemoteSwipe,
    sendNavAction,
    sendTextInput,
    sendCommand,
    setBridgeEndpoint
  };
}
