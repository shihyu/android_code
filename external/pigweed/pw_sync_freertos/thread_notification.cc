// Copyright 2021 The Pigweed Authors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License. You may obtain a copy of
// the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations under
// the License.

#include "pw_sync/thread_notification.h"

#include "FreeRTOS.h"
#include "pw_assert/check.h"
#include "pw_interrupt/context.h"
#include "pw_sync_freertos/config.h"
#include "task.h"

namespace pw::sync {
namespace {

BaseType_t WaitForNotification(TickType_t xTicksToWait) {
#ifdef configTASK_NOTIFICATION_ARRAY_ENTRIES
  return xTaskNotifyWaitIndexed(
      pw::sync::freertos::config::kThreadNotificationIndex,
      0,        // Clear no bits on entry.
      0,        // Clear no bits on exit.
      nullptr,  // Don't care about the notification value.
      xTicksToWait);
#else   // !configTASK_NOTIFICATION_ARRAY_ENTRIES
  return xTaskNotifyWait(0,        // Clear no bits on entry.
                         0,        // Clear no bits on exit.
                         nullptr,  // Don't care about the notification value.
                         xTicksToWait);
#endif  // configTASK_NOTIFICATION_ARRAY_ENTRIES
}

}  // namespace

void ThreadNotification::acquire() {
  // Enforce the pw::sync::ThreadNotification IRQ contract.
  PW_DCHECK(!interrupt::InInterruptContext());

  // Enforce that only a single thread can block at a time.
  PW_DCHECK(native_type_.blocked_thread == nullptr);

  // Ensure that no one forgot to clean up nor corrupted the task notification
  // state in the TCB.
  PW_DCHECK(xTaskNotifyStateClear(nullptr) == pdFALSE);

  taskENTER_CRITICAL();
  if (native_type_.notified) {
    native_type_.notified = false;
    taskEXIT_CRITICAL();
    return;
  }
  // Not notified yet, set the task handle for a one-time notification.
  native_type_.blocked_thread = xTaskGetCurrentTaskHandle();
  taskEXIT_CRITICAL();

#if INCLUDE_vTaskSuspend == 1  // This means portMAX_DELAY is indefinite.
  const BaseType_t result = WaitForNotification(portMAX_DELAY);
  PW_DCHECK_UINT_EQ(result, pdTRUE);
#else   // INCLUDE_vTaskSuspend != 1
  while (WaitForNotification(portMAX_DELAY) == pdFALSE) {
  }
#endif  // INCLUDE_vTaskSuspend

  taskENTER_CRITICAL();
  // The task handle was cleared by the notifier.
  // Note that this may hide another notification, however this is considered
  // a form of notification saturation just like as if this happened before
  // acquire() was invoked.
  native_type_.notified = false;
  taskEXIT_CRITICAL();
}

void ThreadNotification::release() {
  if (!interrupt::InInterruptContext()) {  // Task context
    taskENTER_CRITICAL();
    if (native_type_.blocked_thread != nullptr) {
#ifdef configTASK_NOTIFICATION_ARRAY_ENTRIES
      xTaskNotifyIndexed(native_type_.blocked_thread,
                         pw::sync::freertos::config::kThreadNotificationIndex,
                         0u,
                         eNoAction);
#else   // !configTASK_NOTIFICATION_ARRAY_ENTRIES
      xTaskNotify(native_type_.blocked_thread, 0u, eNoAction);
#endif  // configTASK_NOTIFICATION_ARRAY_ENTRIES

      native_type_.blocked_thread = nullptr;
    }
    native_type_.notified = true;
    taskEXIT_CRITICAL();
    return;
  }

  // Interrupt context
  const UBaseType_t saved_interrupt_mask = taskENTER_CRITICAL_FROM_ISR();
  if (native_type_.blocked_thread != nullptr) {
    BaseType_t woke_higher_task = pdFALSE;

#ifdef configTASK_NOTIFICATION_ARRAY_ENTRIES
    xTaskNotifyIndexedFromISR(
        native_type_.blocked_thread,
        pw::sync::freertos::config::kThreadNotificationIndex,
        0u,
        eNoAction,
        &woke_higher_task);
#else   // !configTASK_NOTIFICATION_ARRAY_ENTRIES
    xTaskNotifyFromISR(
        native_type_.blocked_thread, 0u, eNoAction, &woke_higher_task);
#endif  // configTASK_NOTIFICATION_ARRAY_ENTRIES

    native_type_.blocked_thread = nullptr;
    portYIELD_FROM_ISR(woke_higher_task);
  }
  native_type_.notified = true;
  taskEXIT_CRITICAL_FROM_ISR(saved_interrupt_mask);
}

}  // namespace pw::sync
