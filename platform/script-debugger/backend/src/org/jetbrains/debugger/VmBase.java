package org.jetbrains.debugger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.Promise;

public abstract class VmBase implements Vm, Vm.AttachStateManager {
  private EvaluateContext evaluateContext;
  private final DebugEventListener debugListener;

  protected VmBase(@NotNull DebugEventListener debugListener) {
    this.debugListener = debugListener;
  }

  @NotNull
  @Override
  public final synchronized EvaluateContext getEvaluateContext() {
    if (evaluateContext == null) {
      evaluateContext = computeEvaluateContext();
    }
    return evaluateContext;
  }

  @NotNull
  protected abstract EvaluateContext computeEvaluateContext();

  @NotNull
  @Override
  public final DebugEventListener getDebugListener() {
    return debugListener;
  }

  @NotNull
  @Override
  public AttachStateManager getAttachStateManager() {
    return this;
  }

  @Override
  public boolean isAttached() {
    return true;
  }

  @NotNull
  @Override
  public Promise<Void> detach() {
    return Promise.DONE;
  }
}