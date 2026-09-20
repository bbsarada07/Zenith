import React, { useRef, useEffect, useState, useCallback } from 'react';
import { BoundingBox } from '@/lib/schemas/actionSchema';

export interface ScreenCanvasProps {
  latestBitmap: ImageBitmap | null;
  boundingBoxes: BoundingBox[];
  showBoundingBoxes: boolean;
  showTargetOffsets: boolean;
  enablePrivacyMasking: boolean;
  onRemoteTap: (normX: number, normY: number) => void;
  onRemoteSwipe: (startX: number, startY: number, endX: number, endY: number) => void;
  onRemoteLongPress: (normX: number, normY: number) => void;
  onMacroPointerDown?: (normX: number, normY: number, anchorText?: string) => void;
  onMacroPointerUp?: (normX: number, normY: number, startPoint?: { x: number; y: number }) => void;
  isMacroRecording?: boolean;
}

interface TargetRipple {
  id: number;
  x: number;
  y: number;
  radius: number;
  alpha: number;
}

export const ScreenCanvas: React.FC<ScreenCanvasProps> = ({
  latestBitmap,
  boundingBoxes,
  showBoundingBoxes,
  showTargetOffsets,
  enablePrivacyMasking,
  onRemoteTap,
  onRemoteSwipe,
  onRemoteLongPress,
  onMacroPointerDown,
  onMacroPointerUp,
  isMacroRecording = false
}) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);

  // Gesture state
  const isPointerDownRef = useRef<boolean>(false);
  const pointerStartRef = useRef<{ x: number; y: number; time: number } | null>(null);
  const longPressTimerRef = useRef<NodeJS.Timeout | null>(null);
  const isLongPressTriggeredRef = useRef<boolean>(false);

  // Visual Ripples / Crosshairs
  const [ripples, setRipples] = useState<TargetRipple[]>([]);
  const rippleIdCounterRef = useRef<number>(0);

  const addRipple = useCallback((normX: number, normY: number) => {
    if (!showTargetOffsets) return;
    const canvas = canvasRef.current;
    if (!canvas) return;

    const canvasX = normX * canvas.width;
    const canvasY = normY * canvas.height;

    const newRipple: TargetRipple = {
      id: ++rippleIdCounterRef.current,
      x: canvasX,
      y: canvasY,
      radius: 4,
      alpha: 1.0
    };

    setRipples(prev => [...prev.slice(-4), newRipple]);
  }, [showTargetOffsets]);

  // Render Loop on Canvas
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d', { alpha: false });
    if (!ctx) return;

    let animFrameId: number;

    const render = () => {
      // 1. Draw video frame bitmap
      if (latestBitmap) {
        if (canvas.width !== latestBitmap.width || canvas.height !== latestBitmap.height) {
          canvas.width = latestBitmap.width;
          canvas.height = latestBitmap.height;
        }
        ctx.drawImage(latestBitmap, 0, 0, canvas.width, canvas.height);
      } else {
        // Standby screen
        ctx.fillStyle = '#0a0d14';
        ctx.fillRect(0, 0, canvas.width, canvas.height);

        // Cyberpunk grid standby pattern
        ctx.strokeStyle = '#182232';
        ctx.lineWidth = 1;
        const step = 40;
        for (let x = 0; x < canvas.width; x += step) {
          ctx.beginPath();
          ctx.moveTo(x, 0);
          ctx.lineTo(x, canvas.height);
          ctx.stroke();
        }
        for (let y = 0; y < canvas.height; y += step) {
          ctx.beginPath();
          ctx.moveTo(0, y);
          ctx.lineTo(canvas.width, y);
          ctx.stroke();
        }

        ctx.fillStyle = '#00f0ff';
        ctx.font = 'bold 24px -apple-system, monospace';
        ctx.textAlign = 'center';
        ctx.fillText('ZENITH SPATIAL STREAM // STANDBY', canvas.width / 2, canvas.height / 2 - 20);
        ctx.fillStyle = '#8a99ad';
        ctx.font = '16px -apple-system, sans-serif';
        ctx.fillText('Awaiting video stream from Android bridge...', canvas.width / 2, canvas.height / 2 + 15);
      }

      // 2. Render Privacy Masking if enabled
      if (enablePrivacyMasking) {
        ctx.fillStyle = 'rgba(0, 0, 0, 0.88)';
        // Top status bar redaction
        ctx.fillRect(0, 0, canvas.width, 60);
        
        // Redact any boxes with 'password', 'pin', 'code', 'token'
        for (const box of boundingBoxes) {
          const lower = box.label.toLowerCase();
          if (lower.includes('pass') || lower.includes('pin') || lower.includes('code') || lower.includes('otp') || lower.includes('cvv')) {
            const bx = box.x < 1 ? box.x * canvas.width : box.x;
            const by = box.y < 1 ? box.y * canvas.height : box.y;
            const bw = box.width < 1 ? box.width * canvas.width : box.width;
            const bh = box.height < 1 ? box.height * canvas.height : box.height;
            ctx.fillRect(bx - 4, by - 4, bw + 8, bh + 8);
          }
        }
      }

      // 3. Render Bounding Boxes Overlay
      if (showBoundingBoxes && boundingBoxes.length > 0) {
        ctx.lineWidth = 2;
        for (const box of boundingBoxes) {
          const bx = box.x < 1 ? box.x * canvas.width : box.x;
          const by = box.y < 1 ? box.y * canvas.height : box.y;
          const bw = box.width < 1 ? box.width * canvas.width : box.width;
          const bh = box.height < 1 ? box.height * canvas.height : box.height;

          // Box Fill and Border
          ctx.fillStyle = box.isClickable ? 'rgba(0, 240, 255, 0.12)' : 'rgba(255, 230, 0, 0.08)';
          ctx.strokeStyle = box.isClickable ? '#00f0ff' : '#ffe600';
          ctx.fillRect(bx, by, bw, bh);
          ctx.strokeRect(bx, by, bw, bh);

          // Label badge
          if (box.label && bh > 16) {
            ctx.fillStyle = box.isClickable ? '#00f0ff' : '#ffe600';
            ctx.fillRect(bx, Math.max(0, by - 18), Math.min(bw, 140), 18);
            ctx.fillStyle = '#000';
            ctx.font = 'bold 11px monospace';
            ctx.textAlign = 'left';
            const truncated = box.label.length > 18 ? box.label.substring(0, 16) + '..' : box.label;
            ctx.fillText(truncated, bx + 4, Math.max(14, by - 4));
          }
        }
      }

      // 4. Render Target Offset Ripples & Crosshairs
      if (showTargetOffsets && ripples.length > 0) {
        for (const r of ripples) {
          ctx.save();
          // Expanding circle
          ctx.beginPath();
          ctx.arc(r.x, r.y, r.radius, 0, 2 * Math.PI);
          ctx.strokeStyle = `rgba(0, 255, 136, ${r.alpha})`;
          ctx.lineWidth = 3;
          ctx.stroke();

          // Center crosshair
          const crossSize = 12;
          ctx.beginPath();
          ctx.moveTo(r.x - crossSize, r.y);
          ctx.lineTo(r.x + crossSize, r.y);
          ctx.moveTo(r.x, r.y - crossSize);
          ctx.lineTo(r.x, r.y + crossSize);
          ctx.strokeStyle = `rgba(0, 240, 255, ${r.alpha})`;
          ctx.lineWidth = 2;
          ctx.stroke();
          ctx.restore();
        }
      }

      animFrameId = requestAnimationFrame(render);
    };

    render();

    return () => {
      cancelAnimationFrame(animFrameId);
    };
  }, [latestBitmap, boundingBoxes, showBoundingBoxes, showTargetOffsets, enablePrivacyMasking, ripples]);

  // Ripple Animation Tick
  useEffect(() => {
    if (ripples.length === 0) return;

    const interval = setInterval(() => {
      setRipples(prev =>
        prev
          .map(r => ({
            ...r,
            radius: r.radius + 3,
            alpha: Math.max(0, r.alpha - 0.06)
          }))
          .filter(r => r.alpha > 0.05)
      );
    }, 25);

    return () => clearInterval(interval);
  }, [ripples]);

  // Coordinate Conversion Helper
  const getNormalizedCoords = (e: React.PointerEvent<HTMLCanvasElement>): { normX: number; normY: number } => {
    const canvas = canvasRef.current;
    if (!canvas) return { normX: 0.5, normY: 0.5 };
    const rect = canvas.getBoundingClientRect();
    const clickX = e.clientX - rect.left;
    const clickY = e.clientY - rect.top;

    const normX = Math.min(1.0, Math.max(0.0, clickX / rect.width));
    const normY = Math.min(1.0, Math.max(0.0, clickY / rect.height));

    return { normX, normY };
  };

  const handlePointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    canvasRef.current?.setPointerCapture(e.pointerId);

    const { normX, normY } = getNormalizedCoords(e);
    isPointerDownRef.current = true;
    isLongPressTriggeredRef.current = false;
    pointerStartRef.current = { x: normX, y: normY, time: Date.now() };

    // Find closest anchor text for macro
    const closestBox = boundingBoxes.find(b => {
      const bx = b.x < 1 ? b.x : b.x / 1080;
      const by = b.y < 1 ? b.y : b.y / 2400;
      const bw = b.width < 1 ? b.width : b.width / 1080;
      const bh = b.height < 1 ? b.height : b.height / 2400;
      return normX >= bx && normX <= bx + bw && normY >= by && normY <= by + bh;
    });

    if (isMacroRecording && onMacroPointerDown) {
      onMacroPointerDown(normX, normY, closestBox?.label);
    }

    addRipple(normX, normY);

    // Setup Long Press detection (600ms)
    longPressTimerRef.current = setTimeout(() => {
      if (isPointerDownRef.current) {
        isLongPressTriggeredRef.current = true;
        onRemoteLongPress(normX, normY);
      }
    }, 600);
  };

  const handlePointerUp = (e: React.PointerEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    if (longPressTimerRef.current) {
      clearTimeout(longPressTimerRef.current);
      longPressTimerRef.current = null;
    }

    if (!isPointerDownRef.current || !pointerStartRef.current) return;
    isPointerDownRef.current = false;

    const { normX, normY } = getNormalizedCoords(e);
    const start = pointerStartRef.current;
    const dx = normX - start.x;
    const dy = normY - start.y;
    const distance = Math.sqrt(dx * dx + dy * dy);

    if (isMacroRecording && onMacroPointerUp) {
      onMacroPointerUp(normX, normY, { x: start.x, y: start.y });
    }

    if (!isLongPressTriggeredRef.current) {
      if (distance > 0.06) {
        // Classified as Swipe Gesture
        onRemoteSwipe(start.x, start.y, normX, normY);
        addRipple(normX, normY);
      } else {
        // Classified as Single Tap
        onRemoteTap(normX, normY);
      }
    }

    pointerStartRef.current = null;
  };

  return (
    <div
      ref={containerRef}
      style={{
        position: 'relative',
        width: '100%',
        height: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: '#07090e',
        borderRadius: '16px',
        overflow: 'hidden',
        border: '1px solid #1f2737',
        boxShadow: '0 20px 40px rgba(0,0,0,0.6)',
        userSelect: 'none'
      }}
    >
      <canvas
        ref={canvasRef}
        width={1080}
        height={2400}
        onPointerDown={handlePointerDown}
        onPointerUp={handlePointerUp}
        style={{
          maxWidth: '100%',
          maxHeight: '100%',
          objectFit: 'contain',
          aspectRatio: '9 / 19.5',
          cursor: isMacroRecording ? 'crosshair' : 'pointer',
          touchAction: 'none'
        }}
      />
    </div>
  );
};
