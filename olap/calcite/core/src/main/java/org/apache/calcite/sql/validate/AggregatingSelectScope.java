/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.sql.validate;

import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMultiset;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.apache.calcite.sql.SqlUtil.stripAs;

import static java.util.Objects.requireNonNull;

/**
 * Scope for resolving identifiers within a SELECT statement that has a
 * GROUP BY clause.
 *
 * <p>The same set of identifiers are in scope, but it won't allow access to
 * identifiers or expressions which are not group-expressions.
 */
public class AggregatingSelectScope
    extends DelegatingScope implements AggregatingScope {
  //~ Instance fields --------------------------------------------------------

  private final SqlSelect select;
  private final boolean distinct;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates an AggregatingSelectScope.
   *
   * @param selectScope Parent scope
   * @param select      Enclosing SELECT node
   * @param distinct    Whether SELECT is DISTINCT
   */
  AggregatingSelectScope(
      SqlValidatorScope selectScope,
      SqlSelect select,
      boolean distinct) {
    // The select scope is the parent in the sense that all columns which
    // are available in the select scope are available. Whether they are
    // valid as aggregation expressions... now that's a different matter.
    super(selectScope);
    this.select = select;
    this.distinct = distinct;
  }

  //~ Methods ----------------------------------------------------------------

  @Override protected void analyze(SqlValidatorUtil.GroupAnalyzer analyzer) {
    super.analyze(analyzer);

    final ImmutableList.Builder<ImmutableList<ImmutableBitSet>> builder =
        ImmutableList.builder();
    boolean groupByDistinct = false;
    if (select.getGroup() != null) {
      SqlNodeList groupList = select.getGroup();
      // if the DISTINCT keyword of GROUP BY is present it can be the only item
      if (groupList.size() == 1
          && groupList.get(0).getKind() == SqlKind.GROUP_BY_DISTINCT) {
        groupList =
            new SqlNodeList(((SqlCall) groupList.get(0)).getOperandList(),
                groupList.getParserPosition());
        groupByDistinct = true;
      }
      for (SqlNode groupExpr : groupList) {
        SqlValidatorUtil.analyzeGroupItem(this, analyzer, builder,
            groupExpr);
      }
    }

    for (List<ImmutableBitSet> groupSet : Linq4j.product(builder.build())) {
      analyzer.flatGroupSets.add(ImmutableBitSet.union(groupSet));
    }

    // For GROUP BY (), we need a singleton grouping set.
    if (analyzer.flatGroupSets.isEmpty()) {
      analyzer.flatGroupSets.add(ImmutableBitSet.of());
    }

    if (groupByDistinct) {
      assign(analyzer.flatGroupSets, Util.distinctList(analyzer.flatGroupSets));
    }
  }

  /** Replaces the contents of {@code target} collection with the contents of
   * {@code source}. */
  private static <E> void assign(Collection<E> target, Collection<E> source) {
    if (source == target) {
      return;
    }
    target.clear();
    target.addAll(source);
  }

  /**
   * Returns the expressions that are in the GROUP BY clause (or the SELECT
   * DISTINCT clause, if distinct) and that can therefore be referenced
   * without being wrapped in aggregate functions.
   *
   * <p>Also identifies measure expressions, which are not in {@code GROUP BY}
   * but can still be referenced without aggregate functions. (Some dialects
   * require measures to be wrapped in
   * {@link org.apache.calcite.sql.fun.SqlLibraryOperators#AGGREGATE};
   * see {@link SqlValidator.Config#nakedMeasures()}.)
   *
   * <p>The expressions are fully-qualified, and any "*" in select clauses are
   * expanded.
   */
  private void gatherGroupExprs(ImmutableList.Builder<SqlNode> extraExprs,
      ImmutableList.Builder<SqlNode> measureExprs,
      ImmutableList.Builder<SqlNode> groupExprs) {
    if (distinct) {
      // Cannot compute this in the constructor: select list has not been
      // expanded yet.
      assert select.isDistinct();

      // Remove the AS operator so the expressions are consistent with
      // OrderExpressionExpander.
      final SelectScope selectScope = (SelectScope) parent;
      List<SqlNode> expandedSelectList =
          requireNonNull(selectScope.getExpandedSelectList(),
              () -> "expandedSelectList for " + selectScope);
      for (SqlNode selectItem : expandedSelectList) {
        groupExprs.add(stripAs(selectItem));
      }
    } else {
      SqlValidatorUtil.GroupAnalyzer groupAnalyzer = this.groupAnalyzer;
      if (groupAnalyzer != null) {
        // we are in the middle of resolving
        extraExprs.addAll(groupAnalyzer.extraExprs);
        measureExprs.addAll(groupAnalyzer.measureExprs);
        groupExprs.addAll(groupAnalyzer.groupExprs);
      } else {
        final Resolved resolved = this.resolved.get();
        extraExprs.addAll(resolved.extraExprList);
        measureExprs.addAll(resolved.measureExprList);
        groupExprs.addAll(resolved.groupExprList);
      }
    }
  }

  @Override public SqlNode getNode() {
    return select;
  }

  @Override public RelDataType nullifyType(SqlNode node, RelDataType type) {
    final Resolved r = this.resolved.get();
    for (Ord<SqlNode> groupExpr : Ord.zip(r.groupExprList)) {
      if (groupExpr.e.equalsDeep(node, Litmus.IGNORE)) {
        if (r.isNullable(groupExpr.i)) {
          return validator.getTypeFactory().createTypeWithNullability(type,
              true);
        }
      }
    }
    return type;
  }

  @Override public SqlValidatorScope getOperandScope(SqlCall call) {
    if (call.getOperator().isAggregator()) {
      // If we're the 'SUM' node in 'select a + sum(b + c) from t
      // group by a', then we should validate our arguments in
      // the non-aggregating scope, where 'b' and 'c' are valid
      // column references.
      return parent;
    } else {
      // Check whether expression is constant within the group.
      //
      // If not, throws. Example, 'empno' in
      //    SELECT empno FROM emp GROUP BY deptno
      //
      // If it perfectly matches an expression in the GROUP BY
      // clause, we validate its arguments in the non-aggregating
      // scope. Example, 'empno + 1' in
      //
      //   SELECT empno + 1 FROM emp GROUP BY empno + 1

      final boolean matches = checkAggregateExpr(call, false);
      if (matches) {
        return parent;
      }
    }
    return super.getOperandScope(call);
  }

  @Override public boolean checkAggregateExpr(SqlNode expr, boolean deep) {
    // Fully-qualify any identifiers in expr.
    if (deep) {
      expr = validator.expand(expr, this);
    }

    // Make sure expression is valid, throws if not.
    final ImmutableList.Builder<SqlNode> extraExprs = ImmutableList.builder();
    final ImmutableList.Builder<SqlNode> measureExprs = ImmutableList.builder();
    final ImmutableList.Builder<SqlNode> groupExprs = ImmutableList.builder();
    gatherGroupExprs(extraExprs, measureExprs, groupExprs);
    final AggChecker aggChecker =
        new AggChecker(validator, this, extraExprs.build(),
            measureExprs.build(), groupExprs.build(), distinct);
    if (deep) {
      expr.accept(aggChecker);
    }

    // Return whether expression exactly matches one of the group
    // expressions.
    return aggChecker.isGroupExpr(expr);
  }

  @Override public void validateExpr(SqlNode expr) {
    checkAggregateExpr(expr, true);
  }

  /** Information about an aggregating scope that can only be determined
   * after validation has occurred. Therefore it cannot be populated when
   * the scope is created. */
  public static class Resolved {
    public final ImmutableList<SqlNode> extraExprList;
    public final ImmutableList<SqlNode> measureExprList;
    public final ImmutableList<SqlNode> groupExprList;
    public final ImmutableBitSet groupSet;
    public final ImmutableSortedMultiset<ImmutableBitSet> groupSets;
    public final Map<Integer, Integer> groupExprProjection;

    Resolved(List<SqlNode> extraExprList, List<SqlNode> measureExprList,
        List<SqlNode> groupExprList, Iterable<ImmutableBitSet> groupSets,
        Map<Integer, Integer> groupExprProjection) {
      this.extraExprList = ImmutableList.copyOf(extraExprList);
      this.measureExprList = ImmutableList.copyOf(measureExprList);
      this.groupExprList = ImmutableList.copyOf(groupExprList);
      this.groupSet = ImmutableBitSet.range(groupExprList.size());
      this.groupSets = ImmutableSortedMultiset.copyOf(groupSets);
      this.groupExprProjection = ImmutableMap.copyOf(groupExprProjection);
    }

    /** Returns whether a field should be nullable due to grouping sets. */
    public boolean isNullable(int i) {
      return i < groupExprList.size() && !ImmutableBitSet.allContain(groupSets, i);
    }

    /** Returns whether a given expression is equal to one of the grouping
     * expressions. Determines whether it is valid as an operand to GROUPING. */
    public boolean isGroupingExpr(SqlNode operand) {
      return lookupGroupingExpr(operand) >= 0;
    }

    public int lookupGroupingExpr(SqlNode operand) {
      return SqlUtil.indexOfDeep(groupExprList, operand, Litmus.IGNORE);
    }
  }
}
