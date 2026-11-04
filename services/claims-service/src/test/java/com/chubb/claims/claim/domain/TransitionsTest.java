package com.chubb.claims.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TransitionsTest {

  private static final ClaimAction[] COLUMNS = {
    ClaimAction.PICK_UP, ClaimAction.ASSIGN, ClaimAction.ADD_NOTE, ClaimAction.REQUEST_INFO,
    ClaimAction.ANSWER_INFO, ClaimAction.ASSESS, ClaimAction.APPROVE, ClaimAction.REJECT,
    ClaimAction.SETTLE
  };

  /** Independent copy of the LIFECYCLE.md grid. "" = illegal, "same" = unchanged status. */
  private static final String[][] GRID = {
    // PICK_UP, ASSIGN, ADD_NOTE, REQUEST_INFO, ANSWER_INFO, ASSESS, APPROVE, REJECT, SETTLE
    {"SUBMITTED", "UNDER_REVIEW", "UNDER_REVIEW", "", "", "", "", "", "", ""},
    {"UNDER_REVIEW", "", "same", "same", "AWAITING_INFO", "", "ASSESSED", "", "REJECTED", ""},
    {"AWAITING_INFO", "", "same", "same", "", "UNDER_REVIEW", "", "", "", ""},
    {"ASSESSED", "", "same", "same", "AWAITING_INFO", "", "ASSESSED", "APPROVED", "REJECTED", ""},
    {"APPROVED", "", "same", "same", "", "", "", "", "", "SETTLED"},
    {"SETTLED", "", "", "", "", "", "", "", "", ""},
    {"REJECTED", "", "", "", "", "", "", "", "", ""},
  };

  static Stream<Object[]> everyStatusAndAction() {
    List<Object[]> cases = new ArrayList<>();
    for (String[] row : GRID) {
      for (int i = 0; i < COLUMNS.length; i++) {
        cases.add(new Object[] {ClaimStatus.valueOf(row[0]), COLUMNS[i], row[i + 1]});
      }
    }
    return cases.stream();
  }

  @Test
  void gridCoversEveryStatusAndActionExactlyOnce() {
    assertThat(GRID.length).isEqualTo(ClaimStatus.values().length);
    assertThat(COLUMNS).containsExactly(ClaimAction.values());
    assertThat(everyStatusAndAction().count())
        .isEqualTo((long) ClaimStatus.values().length * ClaimAction.values().length);
  }

  @ParameterizedTest(name = "{0} + {1} -> [{2}]")
  @MethodSource("everyStatusAndAction")
  void matchesTheTableExactly(ClaimStatus from, ClaimAction action, String expected) {
    Optional<ClaimStatus> actual = Transitions.next(from, action);
    if (expected.isEmpty()) {
      assertThat(actual).isEmpty();
      assertThatThrownBy(() -> Transitions.require(from, action))
          .isInstanceOfSatisfying(
              DomainException.class,
              e -> assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_TRANSITION));
    } else {
      ClaimStatus target = expected.equals("same") ? from : ClaimStatus.valueOf(expected);
      assertThat(actual).contains(target);
      assertThat(Transitions.require(from, action)).isEqualTo(target);
    }
  }

  @Test
  void terminalStatesAdmitNothing() {
    for (ClaimStatus s : new ClaimStatus[] {ClaimStatus.SETTLED, ClaimStatus.REJECTED}) {
      assertThat(s.isTerminal()).isTrue();
      for (ClaimAction a : ClaimAction.values()) {
        assertThat(Transitions.next(s, a)).isEmpty();
      }
    }
    assertThat(ClaimStatus.APPROVED.isTerminal()).isFalse();
  }

  @Test
  void answeringAlwaysReturnsToUnderReview() {
    assertThat(Transitions.next(ClaimStatus.AWAITING_INFO, ClaimAction.ANSWER_INFO))
        .contains(ClaimStatus.UNDER_REVIEW);
  }

  /** Guards against the code and the written spec drifting apart. */
  @Test
  void codeMatchesLifecycleMarkdown() throws Exception {
    Path spec = Path.of("../../docs/domain/LIFECYCLE.md");
    assertThat(Files.exists(spec)).as("LIFECYCLE.md must be reachable from the module dir, or the code-to-spec check silently skips").isTrue();
    List<String> lines = Files.readAllLines(spec);
    int checked = 0;
    for (String line : lines) {
      if (!line.startsWith("| `")) continue;
      String[] cells = line.split("\\|", -1);
      // cells[0] is empty, cells[1] status, cells[2..10] nine actions, cells[11] empty
      if (cells.length != 12) continue;
      String status = cells[1].replace("`", "").trim();
      if (java.util.Arrays.stream(ClaimStatus.values()).noneMatch(s -> s.name().equals(status))) {
        continue;
      }
      ClaimStatus from = ClaimStatus.valueOf(status);
      for (int i = 0; i < COLUMNS.length; i++) {
        String cell = cells[i + 2].replace("`", "").trim();
        Optional<ClaimStatus> expected =
            cell.isEmpty()
                ? Optional.empty()
                : Optional.of(cell.equals("same") ? from : ClaimStatus.valueOf(cell));
        assertThat(Transitions.next(from, COLUMNS[i]))
            .as("%s + %s", from, COLUMNS[i])
            .isEqualTo(expected);
      }
      checked++;
    }
    assertThat(checked).isEqualTo(ClaimStatus.values().length);
  }
}
