/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.mmtk.plan.markcopy.remset;

import org.mmtk.plan.Plan;
import org.mmtk.plan.Trace;
import org.mmtk.plan.TraceLocal;
import org.mmtk.policy.MarkBlock;
import org.mmtk.policy.Space;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.Log;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;

/**
 * This class implements the core functionality for a transitive
 * closure over the heap graph.
 */
@Uninterruptible
public class MarkCopyRelocationTraceLocal extends TraceLocal {

  public MarkCopyRelocationTraceLocal(Trace trace) {
    super(MarkCopy.SCAN_RELOCATE, trace);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isLive(ObjectReference object) {
    if (object.isNull()) return false;
    if (Space.isInSpace(MarkCopy.MC, object))
      return MarkCopy.markBlockSpace.isLive(object);
    return super.isLive(object);
  }
/*
  @Override
  @Inline
  public void processEdge(ObjectReference source, Address slot) {
    VM.assertions._assert(!Space.isInSpace(Plan.VM_SPACE, source));
    ObjectReference object = slot.loadObjectReference();//VM.activePlan.global().loadObjectReference(slot);
    if (!object.isNull() && Space.isInSpace(MarkCopy.MC, object)) {
      if (ForwardingWord.isForwardedOrBeingForwarded(object)) {
        Log.write(Space.getSpaceForObject(source).getName());
        Log.write(" object ", VM.objectModel.objectStartRef(source));
        Log.write("  ", source);
        Log.write(".", object);
        Log.writeln(" is forwarded");
      }
    }
    super.processEdge(source, slot);
  }
*/
  @Override
  @Inline
  public ObjectReference traceObject(ObjectReference object) {
    if (object.isNull()) return object;
    if (Space.isInSpace(MarkCopy.MC, object))
      return MarkCopy.markBlockSpace.traceRedirectObject(this, object);
    return super.traceObject(object);
  }

  /**
   * Will this object move from this point on, during the current trace ?
   *
   * @param object The object to query.
   * @return True if the object will not move.
   */
  @Override
  public boolean willNotMoveInCurrentCollection(ObjectReference object) {
    if (Space.isInSpace(MarkCopy.MC, object)) {
      return !MarkBlock.relocationRequired(MarkBlock.of(VM.objectModel.objectStartRef(object)));
    } else {
      return super.willNotMoveInCurrentCollection(object);
    }
  }
}