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
package org.mmtk.plan.concurrent.pureg1;

import org.mmtk.plan.MutatorContext;
import org.mmtk.plan.StopTheWorldMutator;
import org.mmtk.policy.CardTable;
import org.mmtk.policy.MarkBlock;
import org.mmtk.policy.Space;
import org.mmtk.utility.Log;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.utility.alloc.MarkBlockAllocator;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.*;

import static org.mmtk.utility.Constants.BYTES_IN_ADDRESS;

/**
 * This class implements <i>per-mutator thread</i> behavior
 * and state for the <i>RegionalCopy</i> plan, which implements a full-heap
 * semi-space collector.<p>
 *
 * Specifically, this class defines <i>RegionalCopy</i> mutator-time allocation
 * and per-mutator thread collection semantics (flushing and restoring
 * per-mutator allocator state).<p>
 *
 * See {@link PureG1} for an overview of the semi-space algorithm.
 *
 * @see PureG1
 * @see PureG1Collector
 * @see StopTheWorldMutator
 * @see MutatorContext
 */
@Uninterruptible
public class PureG1Mutator extends StopTheWorldMutator {
  /****************************************************************************
   * Instance fields
   */
  protected final MarkBlockAllocator mc;
  private static final int REMSET_LOG_BUFFER_SIZE = 256;
  private AddressArray remSetLogBuffer = AddressArray.create(REMSET_LOG_BUFFER_SIZE);
  private int remSetLogBufferCursor = 0;

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Constructor
   */
  public PureG1Mutator() {
    mc = new MarkBlockAllocator(PureG1.markBlockSpace, false);
  }

  /****************************************************************************
   *
   * Mutator-time allocation
   */

  /**
   * {@inheritDoc}
   */
  @Override
  @Inline
  public Address alloc(int bytes, int align, int offset, int allocator, int site) {
    if (allocator == PureG1.ALLOC_MC) {
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(bytes <= MarkBlock.BYTES_IN_BLOCK);
      return mc.alloc(bytes, align, offset);
    } else {
      return super.alloc(bytes, align, offset, allocator, site);
    }
  }

  @Override
  @Inline
  public void postAlloc(ObjectReference object, ObjectReference typeRef, int bytes, int allocator) {
    /*if (VM.objectModel.objectStartRef(object).EQ(Address.fromIntZeroExtend(0x68da4008))) {
      Log.writeln(Plan.gcInProgress() ? "IN GC" : "NOT IN GC");
    }
    VM.assertions._assert(VM.objectModel.objectStartRef(object).NE(Address.fromIntZeroExtend(0x68da4008)));
    */
    //Log.write("Post alloc ", VM.objectModel.objectStartRef(object));
    //Log.writeln(" ~ ", VM.objectModel.getObjectEndAddress(object));
    MarkBlock.Card.updateCardMeta(object);
    if (allocator == PureG1.ALLOC_MC) {
      if (VM.VERIFY_ASSERTIONS)
        VM.assertions._assert(Space.isInSpace(PureG1.MC, object) && MarkBlock.allocated(MarkBlock.of(VM.objectModel.objectStartRef(object))));
      PureG1.markBlockSpace.postAlloc(object, bytes);
    } else {
      super.postAlloc(object, typeRef, bytes, allocator);
    }
  }

  @Override
  public Allocator getAllocatorFromSpace(Space space) {
    if (space == PureG1.markBlockSpace) return mc;
    return super.getAllocatorFromSpace(space);
  }

  /****************************************************************************
   *
   * Collection
   */

  /**
   * {@inheritDoc}
   */
  @Override
  @Inline
  public void collectionPhase(short phaseId, boolean primary) {
    if (phaseId == PureG1.PREPARE) {
      super.collectionPhase(phaseId, primary);
      mc.reset();
      return;
    }

    if (phaseId == PureG1.RELEASE) {
      mc.reset();
      super.collectionPhase(phaseId, primary);
      return;
    }

    if (phaseId == PureG1.RELOCATION_SET_SELECTION_PREPARE) {
      mc.reset();
      return;
    }

    if (phaseId == PureG1.PREPARE_EVACUATION) {
      //Log.writeln("Mutator #", getId());
      //enqueueCurrentRSBuffer();
      return;
    }

    if (phaseId == PureG1.REDIRECT_PREPARE) {
      super.collectionPhase(PureG1.PREPARE, primary);
      mc.reset();
      return;
    }

    if (phaseId == PureG1.RELOCATE_PREPARE) {
      super.collectionPhase(PureG1.PREPARE, primary);
      mc.reset();
      return;
    }
    if (phaseId == PureG1.RELOCATE_RELEASE) {
      super.collectionPhase(PureG1.RELEASE, primary);
      return;
    }

    if (phaseId == PureG1.REDIRECT_RELEASE) {
      super.collectionPhase(PureG1.RELEASE, primary);
      return;
    }
    super.collectionPhase(phaseId, primary);
  }

  @Override
  public void flushRememberedSets() {
    mc.reset();
  }

  @Override
  public void deinitMutator() {
    enqueueCurrentRSBuffer();
  }

  @Inline
  public void enqueueCurrentRSBuffer() {
    ConcurrentRemSetRefinement.enqueueFilledRSBuffer(remSetLogBuffer);
    remSetLogBuffer = AddressArray.create(REMSET_LOG_BUFFER_SIZE);
    remSetLogBufferCursor = 0;
  }

  //static Monitor lock = VM.newLock("awewwenyu");

  @Inline
  private void markAndEnqueueCard(Address card) {
    //lock.lock();
    if (CardTable.attemptToMarkCard(card, true)) {
      if (card.EQ(Address.fromIntZeroExtend(0x68019200))) {
        Log.write("Mark card ", card);
        Log.writeln(" ", getId());
      }
      //VM.assertions._assert(!(Plan.gcInProgress() && card.NE(Address.fromIntZeroExtend(0x68019400))));
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(remSetLogBufferCursor < remSetLogBuffer.length());
      remSetLogBuffer.set(remSetLogBufferCursor, card);
      remSetLogBufferCursor += 1;
      // VM.assertions._assert(remSetLogBuffer.get(remSetLogBufferCursor - 1).NE(Address.fromIntZeroExtend(0x601ea600)));
      if (remSetLogBufferCursor >= remSetLogBuffer.length()) {
        //remSetLogBuffer = AddressArray.create(REMSET_LOG_BUFFER_SIZE);
        //remSetLogBufferCursor = 0;
        enqueueCurrentRSBuffer();
      }
    } else {
      //VM.assertions._assert(CardTable.cardIsMarked(card));
    }

    //lock.unlock();

  }

  @Inline
  private void checkCrossRegionPointer(ObjectReference src, Address slot, Address value) {
    //if (VM.activePlan.global().in)
    // if (Plan.gcInProgress()) return;
    /*if (src.toAddress().EQ(Address.fromIntZeroExtend(0x68da4014))) {
      Log.writeln(Plan.gcInProgress() ? "IN GC" : "NOT IN GC");
    }
    0x68da4014)));
    VM.assertions._assert(VM.objectModel.objectStartRef(src).NE(Address.fromIntZeroExtend(0x68da4008)));*/
    /*if (src.toAddress().EQ(Address.fromIntZeroExtend(0x680a54cc))) {
      Log.write("(", src);
      Log.write(" ");
      Log.write(src.isNull() ? "?" : Space.getSpaceForObject(src).getName());
      Log.write(").", slot);
      Log.write(" = ");
      Log.write("(", value);
      Log.write(" ");
      Log.write(value.isZero() ? "?" : Space.getSpaceForAddress(value).getName());
      Log.writeln(")");
    };*/
    if (VM.VERIFY_ASSERTIONS) {
      if (!value.isZero() && Space.isInSpace(PureG1.MC, value) && !MarkBlock.allocated(MarkBlock.of(VM.objectModel.objectStartRef(value.toObjectReference())))) {
        Log.write(src);
        Log.write(".", slot);
        Log.write(" = ");
        Log.writeln(value);
        VM.objectModel.dumpObject(src);
        VM.objectModel.dumpObject(value.toObjectReference());
        Log.write("Use of dead object ", value);
        Log.writeln(", which is in released block ", MarkBlock.of(value));
        VM.assertions._assert(false);
      }
    }
    if (!src.isNull() && !slot.isZero() && !value.isZero()) {
      Word tmp = slot.toWord().xor(value.toWord());
      tmp = tmp.rshl(MarkBlock.LOG_BYTES_IN_BLOCK);
      tmp = value.isZero() ? Word.zero() : tmp;
      if (tmp.isZero()) return;
      if (Space.isInSpace(PureG1.MC, value)) {
        /*if (src.toAddress().EQ(Address.fromIntZeroExtend(0x680a54cc))) {
          Log.writeln("Add card ", MarkBlock.Card.of(VM.objectModel.objectStartRef(src)));
        }*/
        MarkBlock.Card.updateCardMeta(src);
        markAndEnqueueCard(MarkBlock.Card.of(VM.objectModel.objectStartRef(src)));
      }
    }
  }

  @Inline
  @Override
  public void objectReferenceWrite(ObjectReference src, Address slot, ObjectReference value, Word metaDataA, Word metaDataB, int mode) {
    checkCrossRegionPointer(src, slot, value.toAddress());
    VM.barriers.objectReferenceWrite(src, value, metaDataA, metaDataB, mode);
  }

  @Inline
  @Override
  public boolean objectReferenceTryCompareAndSwap(ObjectReference src, Address slot, ObjectReference old, ObjectReference value, Word metaDataA, Word metaDataB, int mode) {
    boolean result = VM.barriers.objectReferenceTryCompareAndSwap(src, old, value, metaDataA, metaDataB, mode);
    checkCrossRegionPointer(src, slot, value.toAddress());
    return result;
  }

  /**
   * {@inheritDoc}
   *
   * @param src The source of the values to be copied
   * @param srcOffset The offset of the first source address, in
   * bytes, relative to <code>src</code> (in principle, this could be
   * negative).
   * @param dst The mutated object, i.e. the destination of the copy.
   * @param dstOffset The offset of the first destination address, in
   * bytes relative to <code>tgt</code> (in principle, this could be
   * negative).
   * @param bytes The size of the region being copied, in bytes.
   */
  @Inline
  @Override
  public boolean objectReferenceBulkCopy(ObjectReference src, Offset srcOffset, ObjectReference dst, Offset dstOffset, int bytes) {
    Address srcCursor = src.toAddress().plus(srcOffset);
    Address cursor = dst.toAddress().plus(dstOffset);
    Address limit = cursor.plus(bytes);
    while (cursor.LT(limit)) {
      checkCrossRegionPointer(dst, cursor, srcCursor.loadAddress());
      cursor = cursor.plus(BYTES_IN_ADDRESS);
      srcCursor = srcCursor.plus(BYTES_IN_ADDRESS);
    }
    return false;
  }

  @Inline
  PureG1 global() {
    return (PureG1) VM.activePlan.global();
  }
}
