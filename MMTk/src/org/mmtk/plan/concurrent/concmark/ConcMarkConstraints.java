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
package org.mmtk.plan.concurrent.concmark;

import org.mmtk.plan.StopTheWorldConstraints;
import org.mmtk.plan.concurrent.ConcurrentConstraints;
import org.mmtk.policy.MarkRegion;
import org.mmtk.policy.MarkRegionSpace;
import org.vmmagic.pragma.Uninterruptible;

/**
 * SemiSpace common constants.
 */
@Uninterruptible
public class ConcMarkConstraints extends ConcurrentConstraints {
  @Override
  public boolean movesObjects() {
    return true;
  }
  @Override
  public boolean needsForwardAfterLiveness() {
    return true;
  }
  @Override
  public int gcHeaderBits() {
    return MarkRegionSpace.LOCAL_GC_BITS_REQUIRED;
  }
  @Override
  public int gcHeaderWords() {
    return MarkRegionSpace.GC_HEADER_WORDS_REQUIRED;
  }
  @Override
  public int numSpecializedScans() {
    return 2;
  }
  @Override
  public int maxNonLOSDefaultAllocBytes() {
    return MarkRegion.BYTES_IN_REGION;
  }
}