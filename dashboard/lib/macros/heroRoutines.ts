import { ActionStep, ActionStepSchema } from '@/lib/schemas/actionSchema';
import { MacroRecipe } from '@/lib/macroRecorder';

/**
 * Hero Use Case Automation Routines:
 * 1. Routine A (Productivity): Read unread WhatsApp -> Summarize Intent -> Paste into System Notes.
 * 2. Routine B (System Operations): Open Settings -> Toggle Dark Mode -> Navigate to Battery -> Log Health Status.
 * 3. Routine C (Cross-App Flow): Search target contact -> Initiate spatial call trigger -> Audit trace.
 */
export const HERO_ROUTINES: Record<string, MacroRecipe> = {
  ROUTINE_A_PRODUCTIVITY: {
    id: 'routine_a_productivity',
    name: 'Routine A: WhatsApp Summary to Notes',
    createdAt: Date.now(),
    totalDurationMs: 14000,
    steps: [
      ActionStepSchema.parse({
        id: 'r_a_step_1_launch_wa',
        action: 'CLICK',
        target: { x: 742, y: 1608, label: 'WhatsApp', confidence: 0.96 },
        description: 'Launch WhatsApp',
        timeoutMs: 3000
      }),
      ActionStepSchema.parse({
        id: 'r_a_step_2_open_unread',
        action: 'CLICK',
        target: { x: 500, y: 380, label: 'Unread Chat', confidence: 0.94 },
        description: 'Open latest unread message thread',
        timeoutMs: 3000
      }),
      ActionStepSchema.parse({
        id: 'r_a_step_3_nav_home',
        action: 'GO_HOME',
        description: 'Navigate Home',
        timeoutMs: 1500
      }),
      ActionStepSchema.parse({
        id: 'r_a_step_4_open_notes',
        action: 'CLICK',
        target: { x: 260, y: 1200, label: 'System Notes', confidence: 0.95 },
        description: 'Open Notes app',
        timeoutMs: 3000
      }),
      ActionStepSchema.parse({
        id: 'r_a_step_5_create_note',
        action: 'CLICK',
        target: { x: 920, y: 2200, label: 'New Note (+)', confidence: 0.98 },
        description: 'Create new note entry',
        timeoutMs: 2500
      }),
      ActionStepSchema.parse({
        id: 'r_a_step_6_paste_summary',
        action: 'TYPE',
        payload: 'Summary: Client confirmed Q3 spatial automation review for 5:00 PM.',
        description: 'Inject synthesized summary into Notes',
        timeoutMs: 2500
      })
    ]
  },

  ROUTINE_B_SYSTEM_OPS: {
    id: 'routine_b_system_ops',
    name: 'Routine B: Dark Mode & Battery Health Audit',
    createdAt: Date.now(),
    totalDurationMs: 12000,
    steps: [
      ActionStepSchema.parse({
        id: 'r_b_step_1_settings',
        action: 'CLICK',
        target: { x: 260, y: 1608, label: 'Settings', confidence: 0.97 },
        description: 'Launch System Settings',
        timeoutMs: 3000
      }),
      ActionStepSchema.parse({
        id: 'r_b_step_2_display',
        action: 'CLICK',
        target: { x: 500, y: 620, label: 'Display & Brightness', confidence: 0.94 },
        description: 'Navigate to Display settings',
        timeoutMs: 3000
      }),
      ActionStepSchema.parse({
        id: 'r_b_step_3_toggle_dark',
        action: 'CLICK',
        target: { x: 880, y: 440, label: 'Dark Mode Switch', confidence: 0.95 },
        description: 'Toggle Dark Theme ON',
        timeoutMs: 2000
      }),
      ActionStepSchema.parse({
        id: 'r_b_step_4_nav_back',
        action: 'GO_BACK',
        description: 'Navigate back to Main Settings',
        timeoutMs: 1500
      }),
      ActionStepSchema.parse({
        id: 'r_b_step_5_battery',
        action: 'CLICK',
        target: { x: 500, y: 1100, label: 'Battery & Power', confidence: 0.93 },
        description: 'Navigate to Battery health diagnostics',
        timeoutMs: 3000
      })
    ]
  },

  ROUTINE_C_CROSS_APP_CALL: {
    id: 'routine_c_cross_app_call',
    name: 'Routine C: Contact Search & Spatial Call Trigger',
    createdAt: Date.now(),
    totalDurationMs: 11000,
    steps: [
      ActionStepSchema.parse({
        id: 'r_c_step_1_phone_app',
        action: 'CLICK',
        target: { x: 120, y: 2280, label: 'Phone Dialer', confidence: 0.97 },
        description: 'Launch Phone application',
        timeoutMs: 3000
      }),
      ActionStepSchema.parse({
        id: 'r_c_step_2_search_bar',
        action: 'CLICK',
        target: { x: 500, y: 160, label: 'Search Contacts', confidence: 0.95 },
        description: 'Focus contact search',
        timeoutMs: 2000
      }),
      ActionStepSchema.parse({
        id: 'r_c_step_3_type_contact',
        action: 'TYPE',
        payload: 'Bhavya Sarada',
        description: 'Input contact name',
        timeoutMs: 2500
      }),
      ActionStepSchema.parse({
        id: 'r_c_step_4_call_btn',
        action: 'CLICK',
        target: { x: 880, y: 380, label: 'Call Button', confidence: 0.96 },
        description: 'Trigger voice call',
        timeoutMs: 3000
      })
    ]
  }
};
