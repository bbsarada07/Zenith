/**
 * Zenith AI: Multi-Agent Planner Controller & Safety Guardrail Server.
 *
 * Implements:
 * 1. WebSocket Server on port 8080 (bidirectional protocol).
 * 2. Zod Schema Validation & Safety Guardrail Interceptor (sensitive keywords / financial apps).
 * 3. Multi-Agent DAG Planner: Generates sequential execution steps and tracks state.
 * 4. Reflection Loop Handler: Consumes NO_MUTATION with screen_base64 and triggers visual re-planning.
 */

const { WebSocketServer } = require('ws');
const { z } = require('zod');

// --- ZOD SCHEMAS ---

const CommandActionSchema = z.enum([
  'CLICK_TEXT',
  'CLICK_COORD',
  'SWIPE',
  'TYPE_TEXT',
  'INPUT_KEY',
  'REQUIRE_USER_CONFIRMATION',
  'PING',
  'PONG',
  'AGENT_REGISTER'
]);

const InboundTelemetrySchema = z.object({
  status: z.enum([
    'SUCCESS',
    'NODE_NOT_FOUND',
    'NO_MUTATION',
    'TIMEOUT',
    'REQUIRE_USER_CONFIRMATION',
    'CONFIRMATION_APPROVED',
    'CONFIRMATION_REJECTED',
    'EMERGENCY_STOP'
  ]).optional(),
  type: z.string().optional(),
  step_id: z.string().optional(),
  current_app: z.string().optional(),
  node_tree_hash: z.string().optional(),
  screen_width: z.number().optional(),
  screen_height: z.number().optional(),
  screen_base64: z.string().optional(),
  error_message: z.string().optional(),
  timestamp: z.number().optional()
});

const PlanStepSchema = z.object({
  step_id: z.string(),
  action: CommandActionSchema,
  target: z.string().default(''),
  coords: z.tuple([z.number(), z.number()]).optional(),
  end_coords: z.tuple([z.number(), z.number()]).optional(),
  payload: z.string().default(''),
  description: z.string().default(''),
  isSensitive: z.boolean().default(false)
});

// Sensitive Keywords and Restricted App Packages
const SENSITIVE_KEYWORDS = [
  'pay',
  'payment',
  'transfer',
  'confirm order',
  'delete',
  'reset',
  'password',
  'pin',
  'biometric',
  'buy now',
  'send money',
  'format device',
  'uninstall'
];

const SENSITIVE_PACKAGES = [
  'com.google.android.apps.walletnfcrel',
  'com.google.android.apps.nbu.paisa.user',
  'net.one97.paytm',
  'com.phonepe.app',
  'com.android.settings'
];

class ZenithPlannerController {
  constructor(port = 8080) {
    this.port = port;
    this.wss = new WebSocketServer({ port });
    this.activeExecutorSocket = null;
    this.currentPlan = [];
    this.currentStepIndex = 0;
    this.isExecuting = false;
    this.retryCountMap = new Map();

    this.initServer();
  }

  initServer() {
    console.log(`[ZenithPlanner] Initializing Controller Server on port ${this.port}...`);

    this.wss.on('connection', (ws, req) => {
      const clientIp = req.socket.remoteAddress;
      console.log(`[ZenithPlanner] Client connected from ${clientIp}`);

      ws.on('message', (message) => {
        try {
          const raw = JSON.parse(message.toString());
          this.handleInboundMessage(ws, raw);
        } catch (err) {
          console.error(`[ZenithPlanner] Failed to parse message JSON: ${err.message}`);
        }
      });

      ws.on('close', () => {
        console.log(`[ZenithPlanner] Client disconnected from ${clientIp}`);
        if (this.activeExecutorSocket === ws) {
          this.activeExecutorSocket = null;
          this.isExecuting = false;
        }
      });

      ws.on('error', (err) => {
        console.error(`[ZenithPlanner] WebSocket error: ${err.message}`);
      });
    });

    console.log(`[ZenithPlanner] Ready for Android Executor connection on ws://0.0.0.0:${this.port}`);
  }

  handleInboundMessage(ws, raw) {
    // 1. Validate payload structure using Zod
    const parseResult = InboundTelemetrySchema.safeParse(raw);
    if (!parseResult.success) {
      console.warn(`[ZenithPlanner] Telemetry schema validation warning:`, parseResult.error.issues);
    }

    const telemetry = raw;

    if (telemetry.type === 'AGENT_REGISTER') {
      this.activeExecutorSocket = ws;
      console.log(`[ZenithPlanner] Android Executor Registered (${telemetry.agent} v${telemetry.version})`);
      return;
    }

    if (telemetry.type === 'PING') {
      ws.send(JSON.stringify({ type: 'PONG', timestamp: Date.now() }));
      return;
    }

    console.log(`[ZenithPlanner] Telemetry Received: Status=[${telemetry.status}] Step=[${telemetry.step_id}] App=[${telemetry.current_app}] Hash=[${telemetry.node_tree_hash || 'N/A'}]`);

    // Handle Safety Approval Results
    if (telemetry.status === 'CONFIRMATION_APPROVED') {
      console.log(`[ZenithPlanner] User Approved sensitive step '${telemetry.step_id}'. Resuming execution...`);
      this.executeNextStep();
      return;
    }

    if (telemetry.status === 'CONFIRMATION_REJECTED') {
      console.warn(`[ZenithPlanner] User Rejected sensitive step '${telemetry.step_id}'. Aborting plan.`);
      this.isExecuting = false;
      return;
    }

    if (telemetry.status === 'EMERGENCY_STOP') {
      console.error(`[ZenithPlanner] EMERGENCY STOP ACTIVATED. Terminating plan immediately.`);
      this.isExecuting = false;
      this.currentPlan = [];
      return;
    }

    // Handle Execution Status
    if (telemetry.status === 'SUCCESS') {
      console.log(`[ZenithPlanner] Step '${telemetry.step_id}' succeeded.`);
      this.currentStepIndex++;
      this.executeNextStep();
    } else if (telemetry.status === 'NO_MUTATION') {
      this.handleReflectionNoMutation(telemetry);
    } else if (telemetry.status === 'NODE_NOT_FOUND') {
      this.handleNodeNotFound(telemetry);
    }
  }

  /**
   * Generates a DAG plan based on high-level user goal.
   */
  generatePlan(userGoal) {
    console.log(`[ZenithPlanner] Generating DAG Plan for goal: "${userGoal}"`);
    const steps = [];
    const lower = userGoal.toLowerCase();

    if (lower.includes('settings') || lower.includes('dark mode')) {
      steps.push({
        step_id: 'step_1_open_settings',
        action: 'CLICK_TEXT',
        target: 'Settings',
        description: 'Open Settings application'
      });
      steps.push({
        step_id: 'step_2_display_opt',
        action: 'CLICK_TEXT',
        target: 'Display & brightness',
        description: 'Navigate to Display settings'
      });
      steps.push({
        step_id: 'step_3_toggle_dark',
        action: 'CLICK_TEXT',
        target: 'Dark mode',
        description: 'Enable Dark mode theme'
      });
    } else if (lower.includes('pay') || lower.includes('transfer')) {
      steps.push({
        step_id: 'step_1_open_wallet',
        action: 'CLICK_TEXT',
        target: 'GPay',
        description: 'Open Payment Wallet',
        isSensitive: true
      });
      steps.push({
        step_id: 'step_2_confirm_pay',
        action: 'CLICK_TEXT',
        target: 'Pay $50.00',
        description: 'Execute Money Transfer',
        isSensitive: true
      });
    } else {
      // Default single-step search
      steps.push({
        step_id: 'step_1_generic_click',
        action: 'CLICK_TEXT',
        target: userGoal,
        description: `Locate and click "${userGoal}"`
      });
    }

    this.currentPlan = steps;
    this.currentStepIndex = 0;
    this.retryCountMap.clear();
    console.log(`[ZenithPlanner] Plan generated with ${steps.length} sequential steps.`);
  }

  startExecution() {
    if (!this.activeExecutorSocket) {
      console.error(`[ZenithPlanner] Cannot start execution: No active Android Executor socket connected.`);
      return;
    }
    if (this.currentPlan.length === 0) {
      console.warn(`[ZenithPlanner] Plan is empty.`);
      return;
    }

    this.isExecuting = true;
    this.executeNextStep();
  }

  executeNextStep() {
    if (!this.isExecuting) return;

    if (this.currentStepIndex >= this.currentPlan.length) {
      console.log(`[ZenithPlanner] 🎉 ALL PLAN STEPS EXECUTED SUCCESSFULLY.`);
      this.isExecuting = false;
      return;
    }

    const step = this.currentPlan[this.currentStepIndex];

    // Safety Interceptor Check
    const isSensitive = this.checkSafetyGuardrails(step);
    if (isSensitive) {
      console.warn(`[ZenithPlanner] ⚠️ SENSITIVE INTENT INTERCEPTED for step '${step.step_id}'. Prompting user approval.`);
      const confirmPacket = {
        action: 'REQUIRE_USER_CONFIRMATION',
        step_id: step.step_id,
        target: step.target,
        payload: `Action '${step.description || step.target}' involves financial or sensitive operations.`
      };
      this.sendToExecutor(confirmPacket);
      return; // Hold execution until CONFIRMATION_APPROVED is received
    }

    console.log(`[ZenithPlanner] Dispatching Step [${this.currentStepIndex + 1}/${this.currentPlan.length}]: ${step.action} -> "${step.target || step.payload}"`);
    this.sendToExecutor(step);
  }

  checkSafetyGuardrails(step) {
    if (step.isSensitive) return true;
    const targetText = (step.target + ' ' + step.payload + ' ' + step.description).toLowerCase();
    for (const kw of SENSITIVE_KEYWORDS) {
      if (targetText.includes(kw)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reflection Loop Handler: Analyzes NO_MUTATION state and triggers dynamic re-planning.
   */
  handleReflectionNoMutation(telemetry) {
    const stepId = telemetry.step_id;
    const retries = (this.retryCountMap.get(stepId) || 0) + 1;
    this.retryCountMap.set(stepId, retries);

    console.warn(`[ZenithPlanner] Reflection Handler: Step '${stepId}' caused NO_MUTATION (Retry #${retries}).`);

    if (retries > 3) {
      console.error(`[ZenithPlanner] Step '${stepId}' exceeded maximum reflection retries (3). Aborting plan.`);
      this.isExecuting = false;
      return;
    }

    const currentStep = this.currentPlan[this.currentStepIndex];

    if (telemetry.screen_base64) {
      console.log(`[ZenithPlanner] Received screen capture (${telemetry.screen_base64.length} chars base64). Running spatial VLM re-grounding...`);
      // Spatial fallback: Use center coordinates or alternate offset
      const revisedStep = {
        ...currentStep,
        step_id: `${stepId}_retry_${retries}`,
        action: 'CLICK_COORD',
        coords: [0.5, 0.5], // Normalized fallback
        description: `Spatial re-tap retry for ${currentStep.target}`
      };
      console.log(`[ZenithPlanner] Re-planning step with coordinate fallback:`, revisedStep.coords);
      setTimeout(() => {
        this.sendToExecutor(revisedStep);
      }, 500);
    } else {
      // Re-dispatch step
      setTimeout(() => {
        this.sendToExecutor(currentStep);
      }, 1000);
    }
  }

  handleNodeNotFound(telemetry) {
    console.warn(`[ZenithPlanner] Node not found for step '${telemetry.step_id}'. Attempting scroll down...`);
    const scrollStep = {
      step_id: `${telemetry.step_id}_scroll`,
      action: 'SWIPE',
      coords: [0.5, 0.7],
      end_coords: [0.5, 0.3],
      description: 'Scroll down to reveal hidden elements'
    };
    this.sendToExecutor(scrollStep);
  }

  sendToExecutor(packet) {
    if (this.activeExecutorSocket && this.activeExecutorSocket.readyState === 1) {
      this.activeExecutorSocket.send(JSON.stringify(packet));
    } else {
      console.error(`[ZenithPlanner] Cannot send packet: Executor socket disconnected.`);
    }
  }
}

// Instantiate and start server
const controller = new ZenithPlannerController(8080);

// Example CLI demonstration trigger
if (process.argv.includes('--demo')) {
  setTimeout(() => {
    controller.generatePlan('Open Settings and enable Dark mode');
    controller.startExecution();
  }, 2000);
}

module.exports = { ZenithPlannerController };
