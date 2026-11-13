// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import org.junit.Test;
public class ActiveOperationLeasesTest {
 @Test public void lateOldFinallyCannotReleaseResumedGeneration(){var active=new ActiveOperationLeases();var first=active.claim("run");first.close();var resumed=active.claim("run");first.close();assertTrue(active.contains("run"));resumed.close();assertFalse(active.contains("run"));}
 @Test public void liveClaimCannotBeReplacedAndReleaseIsIdempotent(){var active=new ActiveOperationLeases();var first=active.claim("run");assertThrows(IllegalStateException.class,()->active.claim("run"));first.close();first.close();assertFalse(active.contains("run"));}
 @Test public void DifferentRunsKeepSeparateOwnership(){var active=new ActiveOperationLeases();var one=active.claim("one");var two=active.claim("two");one.close();assertTrue(active.contains("two"));two.close();assertFalse(active.contains("two"));}
}
