import { ActionStep, ActionStepSchema } from '@/lib/schemas/actionSchema';

export interface RecordedTouchPoint {
  x: number; // Normalized (0.0 - 1.0)
  y: number;
  timestamp: number;
  type: 'DOWN' | 'MOVE' | 'UP' | 'CLICK' | 'SWIPE' | 'TYPE';
  payload?: string;
  anchorText?: string;
}

export interface MacroRecipe {
  id: string;
  name: string;
  createdAt: number;
  totalDurationMs: number;
  steps: ActionStep[];
}

export class MacroRecorder {
  private isRecording: boolean = false;
  private startTime: number = 0;
  private recordedPoints: RecordedTouchPoint[] = [];
  private onStateChangeCallback?: (isRecording: boolean, count: number) => void;

  constructor(onStateChange?: (isRecording: boolean, count: number) => void) {
    this.onStateChangeCallback = onStateChange;
  }

  public start(): void {
    this.isRecording = true;
    this.startTime = Date.now();
    this.recordedPoints = [];
    this.notifyState();
  }

  public stop(): MacroRecipe {
    this.isRecording = false;
    const duration = Date.now() - this.startTime;
    const recipe = this.compileToRecipe('Macro_' + new Date().toISOString().slice(11, 19).replace(/:/g, ''));
    this.notifyState();
    return recipe;
  }

  public recordPointerDown(normX: number, normY: number, anchorText?: string): void {
    if (!this.isRecording) return;
    this.recordedPoints.push({
      x: normX,
      y: normY,
      timestamp: Date.now() - this.startTime,
      type: 'DOWN',
      anchorText
    });
    this.notifyState();
  }

  public recordPointerUp(normX: number, normY: number, startPoint?: { x: number; y: number }): void {
    if (!this.isRecording) return;
    const now = Date.now() - this.startTime;

    if (startPoint) {
      const dx = Math.abs(normX - startPoint.x);
      const dy = Math.abs(normY - startPoint.y);

      if (dx > 0.05 || dy > 0.05) {
        // Classify as Swipe gesture
        this.recordedPoints.push({
          x: startPoint.x,
          y: startPoint.y,
          timestamp: now,
          type: 'SWIPE',
          payload: JSON.stringify({ endX: normX, endY: normY })
        });
      } else {
        // Classify as Click
        this.recordedPoints.push({
          x: normX,
          y: normY,
          timestamp: now,
          type: 'CLICK'
        });
      }
    } else {
      this.recordedPoints.push({
        x: normX,
        y: normY,
        timestamp: now,
        type: 'UP'
      });
    }

    this.notifyState();
  }

  public recordText(text: string): void {
    if (!this.isRecording) return;
    this.recordedPoints.push({
      x: 0,
      y: 0,
      timestamp: Date.now() - this.startTime,
      type: 'TYPE',
      payload: text
    });
    this.notifyState();
  }

  public compileToRecipe(macroName: string): MacroRecipe {
    const steps: ActionStep[] = [];
    let stepIndex = 1;

    for (const pt of this.recordedPoints) {
      if (pt.type === 'CLICK') {
        steps.push(ActionStepSchema.parse({
          id: `step_${stepIndex++}_click`,
          action: 'CLICK',
          target: {
            x: Math.round(pt.x * 1000),
            y: Math.round(pt.y * 1000),
            label: pt.anchorText || `Tap (${(pt.x * 100).toFixed(1)}%, ${(pt.y * 100).toFixed(1)}%)`,
            confidence: 1.0
          },
          payload: '',
          description: `Tap at normalized (${pt.x.toFixed(3)}, ${pt.y.toFixed(3)})`,
          timeoutMs: 3000,
          expectedMutation: true,
          confidence: 1.0,
          stage: 'PRIMARY'
        }));
      } else if (pt.type === 'SWIPE') {
        let endX = pt.x;
        let endY = pt.y - 0.3;
        try {
          if (pt.payload) {
            const parsed = JSON.parse(pt.payload);
            endX = parsed.endX;
            endY = parsed.endY;
          }
        } catch (_) {}

        steps.push(ActionStepSchema.parse({
          id: `step_${stepIndex++}_swipe`,
          action: 'SWIPE',
          swipe: {
            startX: Math.round(pt.x * 1000),
            startY: Math.round(pt.y * 1000),
            endX: Math.round(endX * 1000),
            endY: Math.round(endY * 1000),
            durationMs: 250
          },
          payload: '',
          description: `Swipe from (${pt.x.toFixed(2)},${pt.y.toFixed(2)}) to (${endX.toFixed(2)},${endY.toFixed(2)})`,
          timeoutMs: 3000,
          expectedMutation: true,
          confidence: 1.0,
          stage: 'PRIMARY'
        }));
      } else if (pt.type === 'TYPE' && pt.payload) {
        steps.push(ActionStepSchema.parse({
          id: `step_${stepIndex++}_type`,
          action: 'TYPE',
          payload: pt.payload,
          description: `Type text "${pt.payload}"`,
          timeoutMs: 3000,
          expectedMutation: false,
          confidence: 1.0,
          stage: 'PRIMARY'
        }));
      }
    }

    const recipe: MacroRecipe = {
      id: `macro_${Date.now()}`,
      name: macroName,
      createdAt: Date.now(),
      totalDurationMs: Date.now() - this.startTime,
      steps
    };

    return recipe;
  }

  public getIsRecording(): boolean {
    return this.isRecording;
  }

  public getPointCount(): number {
    return this.recordedPoints.length;
  }

  private notifyState(): void {
    if (this.onStateChangeCallback) {
      this.onStateChangeCallback(this.isRecording, this.recordedPoints.length);
    }
  }
}
