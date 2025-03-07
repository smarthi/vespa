// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchlib.aggregation;

import com.yahoo.searchlib.expression.AggregationRefNode;
import com.yahoo.searchlib.expression.ExpressionNode;
import com.yahoo.searchlib.expression.ResultNode;
import com.yahoo.vespa.objects.Deserializer;
import com.yahoo.vespa.objects.Identifiable;
import com.yahoo.vespa.objects.ObjectOperation;
import com.yahoo.vespa.objects.ObjectPredicate;
import com.yahoo.vespa.objects.ObjectVisitor;
import com.yahoo.vespa.objects.Serializer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class Group extends Identifiable {

    public static final int classId = registerClass(0x4000 + 90, Group.class);
    private static final ObjectPredicate REF_LOCATOR = new RefLocator();
    private List<Integer> orderByIdx = new ArrayList<>();
    private List<ExpressionNode> orderByExp = new ArrayList<>();
    private List<AggregationResult> aggregationResults = new ArrayList<>();
    private List<Group> children = new ArrayList<>();
    private ResultNode id = null;
    private double rank;
    private int tag = -1;
    private SortType sortType = SortType.UNSORTED;

    /**
     * This tells you if the children are ranked by the pure relevance or by a more complex expression.
     * That indicates if the rank score from the child can be used for ordering.
     *
     * @return true if it ranked by pure relevance.
     */
    public boolean isRankedByRelevance() {
        return orderByIdx.isEmpty();
    }

    /**
     * Merges the content of the given group <b>into</b> this. When this function returns, make sure to call
     * {@link #postMerge(java.util.List, int, int)}.
     *
     * @param firstLevel   The first level to merge.
     * @param currentLevel The current level.
     * @param rhs          The group to merge with.
     */
    public void merge(int firstLevel, int currentLevel, Group rhs) {
        if (rhs.rank > rank) {
            rank = rhs.rank; // keep highest rank
        }
        if (currentLevel >= firstLevel) {
            for (int i = 0, len = aggregationResults.size(); i < len; ++i) {
                aggregationResults.get(i).merge(rhs.aggregationResults.get(i));
            }
        }

        ArrayList<Group> merged = new ArrayList<>();
        Iterator<Group> lhsChild = children.iterator(), rhsChild = rhs.children.iterator();
        if (lhsChild.hasNext() && rhsChild.hasNext()) {
            Group lhsGroup = lhsChild.next();
            Group rhsGroup = rhsChild.next();
            for (; (lhsGroup != null) && (rhsGroup != null); ) {
                int cmp = lhsGroup.getId().compareTo(rhsGroup.getId());
                if (cmp < 0) {
                    merged.add(lhsGroup);
                    lhsGroup = lhsChild.hasNext() ? lhsChild.next() : null;
                } else if (cmp > 0) {
                    merged.add(rhsGroup);
                    rhsGroup = rhsChild.hasNext() ? rhsChild.next() : null;
                } else {
                    lhsGroup.merge(firstLevel, currentLevel + 1, rhsGroup);
                    merged.add(lhsGroup);
                    lhsGroup = lhsChild.hasNext() ? lhsChild.next() : null;
                    rhsGroup = rhsChild.hasNext() ? rhsChild.next() : null;
                }
            }
            if (lhsGroup != null) {
                merged.add(lhsGroup);
            }
            if (rhsGroup != null) {
                merged.add(rhsGroup);
            }
        }
        while (lhsChild.hasNext()) {
            merged.add(lhsChild.next());
        }
        while (rhsChild.hasNext()) {
            merged.add(rhsChild.next());
        }
        children = merged;
    }

    private void executeOrderBy() {
        for (ExpressionNode node : orderByExp) {
            node.prepare();
            node.execute();
        }
    }

    /**
     * After merging, this method will prune all levels so that they do not exceed the configured maximum number of
     * groups per level.
     *
     * @param levels       The specs of all grouping levels.
     * @param firstLevel   The first level to merge.
     * @param currentLevel The current level.
     */
    public void postMerge(List<GroupingLevel> levels, int firstLevel, int currentLevel) {
        if (currentLevel >= firstLevel) {
            for (AggregationResult result : aggregationResults) {
                result.postMerge();
            }
            for (ExpressionNode result : orderByExp) {
                result.execute();
            }
        }
        if (currentLevel < levels.size()) {
            int maxGroups = (int)levels.get(currentLevel).getMaxGroups();
            for (Group group : children) {
                group.executeOrderBy();
            }
            if (maxGroups >= 0 && children.size() > maxGroups) {
                // prune groups
                sortChildrenByRank();
                children = children.subList(0, maxGroups);
                sortChildrenById();
            }
            for (Group group : children) {
                group.postMerge(levels, firstLevel, currentLevel + 1);
            }
        }

    }

    /** Sorts the children by their id, if they are not sorted already. */
    public void sortChildrenById() {
        if (sortType == SortType.BYID) {
            return;
        }
        Collections.sort(children, (Group lhs, Group rhs) -> lhs.compareId(rhs));
        sortType = SortType.BYID;
    }

    /** Sorts the children by their rank, if they are not sorted already. */
    public void sortChildrenByRank() {
        if (sortType == SortType.BYRANK) {
            return;
        }
        Collections.sort(children, (Group lhs, Group rhs) ->  lhs.compareRank(rhs) );

        sortType = SortType.BYRANK;
    }

    /**
     * Returns the label to use for this group. See comment on {@link #setId(com.yahoo.searchlib.expression.ResultNode)}
     * on the rationale of this being a {@link ResultNode}.
     */
    public ResultNode getId() {
        return id;
    }

    /**
     * Sets the label to use for this group. This is a {@link ResultNode} so that a group can be labeled with
     * whatever value the classifier expression returns.
     *
     * @param id the label to set
     * @return this, to allow chaining
     */
    public Group setId(ResultNode id) {
        this.id = id;
        return this;
    }

    /**
     * Sets the relevancy to use for this group.
     *
     * @param rank The rank to set.
     * @return This, to allow chaining.
     */
    public Group setRank(double rank) {
        this.rank = rank;
        return this;
    }

    /** Return the rank score of this group. */
    public double getRank() {
        return rank;
    }

    /**
     * Adds a child group to this.
     *
     * @param child The group to add.
     * @return This, to allow chaining.
     */
    public Group addChild(Group child) {
        if (child == null) {
            throw new IllegalArgumentException("Child can not be null.");
        }
        children.add(child);
        return this;
    }

    /** Returns the list of child groups to this. */
    public List<Group> getChildren() {
        return children;
    }

    /**
     * Returns the tag of this group. This value is set per-level in the grouping request, and then becomes assigned
     * to each group of that level in the grouping result as they are copied from the prototype.
     */
    public int getTag() {
        return tag;
    }

    /**
     * Assigns a tag to this group.
     *
     * @param tag the numerical tag to set
     * @return this, to allow chaining
     */
    public Group setTag(int tag) {
        this.tag = tag;
        return this;
    }

    /**
     * Returns this group's aggregation results.
     *
     * @return the aggregation results
     */
    public List<AggregationResult> getAggregationResults() {
        return aggregationResults;
    }

    /**
     * Adds an aggregation result to this group.
     *
     * @param result the result to add
     * @return this, to allow chaining
     */
    public Group addAggregationResult(AggregationResult result) {
        aggregationResults.add(result);
        return this;
    }

    /**
     * Adds an order-by expression to this group. If the expression is an AggregationResult, it will be added to the
     * list of this group's AggregationResults, and a reference to that expression is added instead. If the
     * AggregationResult is already present, a reference to THAT result is created instead.
     *
     * @param exp the result to add
     * @param asc true to sort ascending, false to sort descending
     * @return this, to allow chaining
     */
    public Group addOrderBy(ExpressionNode exp, boolean asc) {
        if (exp instanceof AggregationResult) {
            exp = new AggregationRefNode((AggregationResult)exp);
        }
        exp.select(REF_LOCATOR, new RefResolver(this));
        orderByExp.add(exp);
        orderByIdx.add((asc ? 1 : -1) * orderByExp.size());
        return this;
    }

    public List<Integer> getOrderByIndexes() {
        return Collections.unmodifiableList(orderByIdx);
    }

    public List<ExpressionNode> getOrderByExpressions() {
        return Collections.unmodifiableList(orderByExp);
    }

    private int compareId(Group rhs) {
        return getId().compareTo(rhs.getId());
    }

    private int compareRank(Group rhs) {
        long diff = 0;
        for (int i = 0, m = orderByIdx.size(); (diff == 0) && (i < m); i++) {
            int rawIndex = orderByIdx.get(i);
            int index = ((rawIndex < 0) ? -rawIndex : rawIndex) - 1;
            diff = orderByExp.get(index).getResult().compareTo(rhs.orderByExp.get(index).getResult());
            diff = diff * rawIndex;
        }
        if (diff < 0) {
            return -1;
        }
        if (diff > 0) {
            return 1;
        }
        return -Double.compare(rank, rhs.rank);
    }

    @Override
    protected int onGetClassId() {
        return classId;
    }

    @Override
    protected void onSerialize(Serializer buf) {
        super.onSerialize(buf);
        serializeOptional(buf, id);
        buf.putDouble(null, rank);
        int sz = orderByIdx.size();
        buf.putInt(null, sz);
        for (Integer index : orderByIdx) {
            buf.putInt(null, index);
        }
        int numResults = aggregationResults.size();
        buf.putInt(null, numResults);
        for (AggregationResult a : aggregationResults) {
            serializeOptional(buf, a);
        }
        int numExpressionResults = orderByExp.size();
        buf.putInt(null, numExpressionResults);
        for (ExpressionNode e : orderByExp) {
            serializeOptional(buf, e);
        }
        int numGroups = children.size();
        buf.putInt(null, numGroups);
        for (Group g : children) {
            g.serializeWithId(buf);
        }
        buf.putInt(null, tag);
    }

    @Override
    protected void onDeserialize(Deserializer buf) {
        super.onDeserialize(buf);
        id = (ResultNode)deserializeOptional(buf);
        rank = buf.getDouble(null);
        orderByIdx.clear();
        int orderByCount = buf.getInt(null);
        for (int i = 0; i < orderByCount; i++) {
            orderByIdx.add(buf.getInt(null));
        }
        int numResults = buf.getInt(null);
        for (int i = 0; i < numResults; i++) {
            AggregationResult e = (AggregationResult)deserializeOptional(buf);
            aggregationResults.add(e);
        }
        int numExpressionResults = buf.getInt(null);
        RefResolver resolver = new RefResolver(this);
        for (int i = 0; i < numExpressionResults; i++) {
            ExpressionNode exp = (ExpressionNode)deserializeOptional(buf);
            exp.select(REF_LOCATOR, resolver);
            orderByExp.add(exp);
        }
        int numGroups = buf.getInt(null);
        for (int i = 0; i < numGroups; i++) {
            Group g = new Group();
            g.deserializeWithId(buf);
            children.add(g);
        }
        tag = buf.getInt(null);
    }

    @Override
    public int hashCode() {
        return super.hashCode() + aggregationResults.hashCode() + children.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!super.equals(obj)) return false;

        Group rhs = (Group)obj;
        if (!equals(id, rhs.id)) return false;
        if (rank != rhs.rank) return false;
        if (!aggregationResults.equals(rhs.aggregationResults)) return false;
        if (!orderByIdx.equals(rhs.orderByIdx)) return false;
        if (!orderByExp.equals(rhs.orderByExp)) return false;
        if (!children.equals(rhs.children)) return false;
        return true;
    }

    @Override
    public Group clone() {
        Group obj = (Group)super.clone();
        if (id != null) {
            obj.id = (ResultNode)id.clone();
        }
        obj.aggregationResults = new ArrayList<>();
        for (AggregationResult result : aggregationResults) {
            obj.aggregationResults.add(result.clone());
        }
        obj.orderByIdx = new ArrayList<>(orderByIdx);
        obj.orderByExp = new ArrayList<>();
        RefResolver resolver = new RefResolver(obj);
        for (ExpressionNode exp : orderByExp) {
            exp = exp.clone();
            exp.select(REF_LOCATOR, resolver);
            obj.orderByExp.add(exp);
        }
        obj.children = new ArrayList<>();
        for (Group child : children) {
            obj.children.add(child.clone());
        }
        return obj;
    }

    @Override
    public void visitMembers(ObjectVisitor visitor) {
        super.visitMembers(visitor);
        visitor.visit("id", id);
        visitor.visit("rank", rank);
        visitor.visit("aggregationresults", aggregationResults);
        visitor.visit("orderby-idx", orderByIdx);
        visitor.visit("orderby-exp", orderByExp);
        visitor.visit("children", children);
        visitor.visit("tag", tag);
    }

    @Override
    public void selectMembers(ObjectPredicate predicate, ObjectOperation operation) {
        for (AggregationResult result : aggregationResults) {
            result.select(predicate, operation);
        }
        for (ExpressionNode exp : orderByExp) {
            exp.select(predicate, operation);
        }
    }

    private enum SortType {
        UNSORTED,
        BYRANK,
        BYID
    }

    private static class RefLocator implements ObjectPredicate {

        @Override
        public boolean check(Object obj) {
            return obj instanceof AggregationRefNode;
        }
    }

    private static class RefResolver implements ObjectOperation {

        final List<AggregationResult> results;

        RefResolver(Group group) {
            this.results = group.aggregationResults;
        }

        @Override
        public void execute(Object obj) {
            AggregationRefNode ref = (AggregationRefNode)obj;
            int idx = ref.getIndex();
            if (idx < 0) {
                AggregationResult res = ref.getExpression();
                idx = indexOf(res);
                if (idx < 0) {
                    idx = results.size();
                    results.add(res);
                }
                ref.setIndex(idx);
            } else {
                ref.setExpression(results.get(idx));
            }
        }

        int indexOf(AggregationResult lhs) {
            int prevTag = lhs.getTag();
            for (int i = 0, len = results.size(); i < len; ++i) {
                AggregationResult rhs = results.get(i);
                lhs.setTag(rhs.getTag());
                if (lhs.equals(rhs)) {
                    return i;
                }
            }
            lhs.setTag(prevTag);
            return -1;
        }
    }

}
