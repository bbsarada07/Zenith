import { useState, useEffect, useRef, useCallback } from 'react';
import { BoundingBox, TelemetryFrame } from '@/lib/schemas/actionSchema';
import { OfficeKitBridge } from '@/lib/bridge/officeKitBridge';

export interface BridgeStreamState {
  bridgeStatus: 'CONNECTED' | 'RECONNECTING' | 'DISCONNECTED';
  bridgeProtocol: string;
  fps: number;
  frameLatencyMs: number;
  npuInferenceTimeMs: number;
  npuUsagePercent: number;
  confidenceMatrix: number;
  executionProvider: string;
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
  onAuditLog?: (
    channel: 'WEBSOCKET_STREAM' | 'OFFICE_KIT_CLIPBOARD' | 'LOCAL_NPU_QNN',
    command: string,
    targetCoords: string | undefined,
    confidence: number,
    statusCode: number,
    latencyMs: number,
    status: 'SUCCESS' | 'RETRY' | 'FAILED'
  ) => void;
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
    bridgeProtocol: 'iQOO Office Kit Bridge (Active)',
    fps: 0,
    frameLatencyMs: 0,
    npuInferenceTimeMs: 16.4,
    npuUsagePercent: 42.5,
    confidenceMatrix: 0.96,
    executionProvider: 'Qualcomm QNN Direct Execution',
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

  // Office Kit Bridge instance
  const officeKitBridgeRef = useRef<OfficeKitBridge>(
    new OfficeKitBridge({
      bridgeHost: initialHost,
      bridgePort: initialPort,
      onAuditLog
    })
  );

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
        npuInferenceTimeMs: data.npuInferenceTimeMs ?? data.npuLatency ?? 16.4,
        npuUsagePercent: data.npuUsagePercent ?? data.npuUtilization ?? 42.5,
        confidenceMatrix: data.confidenceMatrix ?? 0.96,
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
          confidence: b.confidence ?? 0.96,
          resourceId: b.resourceId
        };
      });

      setStreamState(prev => ({
        ...prev,
        boundingBoxes: parsedBoxes
      }));
    }

    // Action Execution Feedback
    if (data.type === 'ACTION_RESULT' || data.status === 'SUCCESS' || data.status === 'NO_MUTATION') {
      const isSuccess = data.status === 'SUCCESS' || data.success === true;
      if (onAuditLog) {
        onAuditLog(
          'WEBSOCKET_STREAM',
          `ACTION: ${data.action || data.command || 'REMOTE_ACTION'}`,
          data.target ? `(${data.target})` : undefined,
          0.96,
          isSuccess ? 200 : 500,
          data.latencyMs || 24,
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

    const wsUrl = `ws://${host}:${port}/ws/stream`;
    setStreamState(prev => ({ ...prev, bridgeStatus: 'RECONNECTING', serverUrl: wsUrl }));

    try {
      const ws = new WebSocket(wsUrl);
      ws.binaryType = 'blob';

      ws.onopen = () => {
        console.log(`[BridgeStream] Connected to Zenith Stream Server at ${wsUrl}`);
        reconnectAttemptsRef.current = 0;
        setStreamState(prev => ({ ...prev, bridgeStatus: 'CONNECTED' }));
        
        // Request initial tree
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
            console.warn('[BridgeStream] Text frame parse warning:', event.data);
          }
        }
      };

      ws.onerror = (err) => {
        console.error('[BridgeStream] WebSocket encountered error:', err);
      };

      ws.onclose = () => {
        setStreamState(prev => ({ ...prev, bridgeStatus: 'DISCONNECTED' }));
        wsRef.current = null;

        if (!isManuallyClosedRef.current) {
          const delay = Math.min(1000 * Math.pow(1.5, reconnectAttemptsRef.current), 15000);
          reconnectAttemptsRef.current++;
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
  const sendRemoteTouch = useCallback(async (normalizedX: number, normalizedY: number, action: 'CLICK' | 'LONG_PRESS' = 'CLICK') => {
    const targetPhysX = Math.round(normalizedX * streamState.screenWidth);
    const targetPhysY = Math.round(normalizedY * streamState.screenHeight);

    // 1. Send via WebSocket
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      const payload = {
        action: action === 'LONG_PRESS' ? 'LONG_PRESS_COORD' : 'REMOTE_TAP',
        coords: [normalizedX, normalizedY],
        x: normalizedX,
        y: normalizedY,
        timestamp: Date.now()
      };
      wsRef.current.send(JSON.stringify(payload));
    }

    // 2. Sync to iQOO Office Kit Shared Clipboard Channel
    await officeKitBridgeRef.current.sendEvent({
      action,
      target: { x: targetPhysX, y: targetPhysY, label: `Touch (${targetPhysX}, ${targetPhysY})` },
      confidence: 0.96,
      channel: 'OFFICE_KIT_SHARED_CLIPBOARD'
    });
  }, [streamState.screenWidth, streamState.screenHeight]);

  // Send Remote Swipe
  const sendRemoteSwipe = useCallback(async (
    startX: number,
    startY: number,
    endX: number,
    endY: number,
    durationMs: number = 250
  ) => {
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
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
    }

    await officeKitBridgeRef.current.sendEvent({
      action: 'SWIPE',
      target: {
        x: Math.round(startX * streamState.screenWidth),
        y: Math.round(startY * streamState.screenHeight),
        label: `Swipe -> (${Math.round(endX * streamState.screenWidth)}, ${Math.round(endY * streamState.screenHeight)})`
      },
      confidence: 0.95,
      channel: 'OFFICE_KIT_SHARED_CLIPBOARD'
    });
  }, [streamState.screenWidth, streamState.screenHeight]);

  // Send Navigation Command
  const sendNavAction = useCallback(async (navAction: 'HOME' | 'BACK' | 'RECENTS' | 'NOTIFICATIONS') => {
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      const payload = {
        action: 'INPUT_KEY',
        target: navAction.toLowerCase(),
        key: navAction,
        timestamp: Date.now()
      };
      wsRef.current.send(JSON.stringify(payload));
    }

    await officeKitBridgeRef.current.sendEvent({
      action: navAction === 'HOME' ? 'GO_HOME' : navAction === 'BACK' ? 'GO_BACK' : 'RECENTS',
      target: { x: 500, y: 2350, label: `Nav Key: ${navAction}` },
      confidence: 1.0,
      channel: 'OFFICE_KIT_SHARED_CLIPBOARD'
    });
  }, []);

  // Send Text Injection
  const sendTextInput = useCallback(async (text: string) => {
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      const payload = {
        action: 'INPUT_TEXT',
        payload: text,
        text,
        timestamp: Date.now()
      };
      wsRef.current.send(JSON.stringify(payload));
    }

    await officeKitBridgeRef.current.sendEvent({
      action: 'TYPE',
      target: { x: 500, y: 1200, label: `Input: "${text}"` },
      confidence: 0.98,
      channel: 'OFFICE_KIT_SHARED_CLIPBOARD'
    });
  }, []);

  const sendCommand = useCallback((commandJson: Record<string, any>) => {
    if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;
    wsRef.current.send(JSON.stringify(commandJson));
  }, []);

  const setBridgeEndpoint = useCallback((newHost: string, newPort: number = 8080) => {
    setHost(newHost);
    setPort(newPort);
    officeKitBridgeRef.current.updateEndpoint(newHost, newPort);
  }, []);

  useEffect(() => {
    const interval = setInterval(updateHeapMetric, 2000);
    return () => clearInterval(interval);
  }, [updateHeapMetric]);

  useEffect(() => {
    if (autoConnect) {
      connect();
    }
    return () => {
      disconnect();
      officeKitBridgeRef.current.destroy();
      if (currentBitmapRef.current) {
        currentBitmapRef.current.close();
        currentBitmapRef.current = null;
      }
    };
  }, [autoConnect, connect, disconnect]);

  return {
    ...streamState,
    officeKitBridge: officeKitBridgeRef.current,
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
