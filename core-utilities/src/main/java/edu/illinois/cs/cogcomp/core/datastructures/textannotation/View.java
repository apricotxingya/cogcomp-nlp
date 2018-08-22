/**
 * This software is released under the University of Illinois/Research and Academic Use License. See
 * the LICENSE file in the root folder for details. Copyright (c) 2016
 *
 * Developed by: The Cognitive Computation Group University of Illinois at Urbana-Champaign
 * http://cogcomp.cs.illinois.edu/
 */
package edu.illinois.cs.cogcomp.core.datastructures.textannotation;

import edu.illinois.cs.cogcomp.core.datastructures.IQueryable;
import edu.illinois.cs.cogcomp.core.datastructures.IntPair;
import edu.illinois.cs.cogcomp.core.datastructures.QueryableList;
import edu.illinois.cs.cogcomp.core.datastructures.ViewNames;
import edu.illinois.cs.cogcomp.core.transformers.ITransformer;
import edu.illinois.cs.cogcomp.core.transformers.Predicate;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.*;

/**
 * This class represents a <i>view</i> of the text. According to the multi-view architecture, any
 * annotation on text can be thought of as a graph, where each node in the graph may correspond to
 * sets of tokens in the text. Labeled directed edges between the nodes denote relations.
 * <p>
 * The nodes are objects of type {@link Constituent} and the edges are objects of type
 * {@link Relation}.
 * <p>
 * <p>
 * This class represents a generic <i>view</i> without any restrictions on the structure of the
 * graph and can be used for general purpose application specific views. However, for most standard
 * annotations can be represented as one of the sub-classes of this class.
 *
 * @author Vivek Srikumar
 * @see Constituent
 * @see Relation
 */
public class View implements Serializable, IQueryable<Constituent> {
    private static final long serialVersionUID = -9076051280012182391L;

    /**
     * The name of this view. While this can be any arbitrary string, if the view represents a
     * standard annotation, use one of the standard names from
     * {@link edu.illinois.cs.cogcomp.core.datastructures.ViewNames}.
     */
    protected final String viewName;

    /**
     * The score of this view, which is generated by the system that annotates this view.
     */
    protected final double score;

    /**
     * The TextAnnotation that is annotated by this view.
     */
    protected final TextAnnotation textAnnotation;

    /**
     * The collection of constituents
     */
    protected final QueryableList<Constituent> constituents;

    /**
     * The collection of relations in this view.
     */
    protected final QueryableList<Relation> relations;
    /**
     * The name of the system that generates this view.
     */
    protected final String viewGenerator;
    /**
     * A inverted index from token ids to constituents in this view that cover that token.
     * <p>
     * The first element corresponds to the token id, the second corresponds to the constituent .
     */
    @SuppressWarnings("rawtypes")
    protected final ArrayList[] tokensToConstituents;
    /**
     * The token id of the leftmost token in this view.
     */
    protected int startSpan;
    /**
     * The token id of the token next to the rightmost token in this view.
     */
    protected int endSpan;

    /**
     * Creates a view for {@code text} called {@code viewName} which is created using a view
     * generator called {@code ViewGenerator} and is assigned a score {@code score}
     *
     * @param viewName The name of this view
     * @param viewGenerator The source of this view
     * @param text The text annotation that this view annotates
     * @param score The score of this view
     */
    public View(String viewName, String viewGenerator, TextAnnotation text, double score) {
        this.viewName = viewName;
        this.viewGenerator = viewGenerator;
        this.textAnnotation = text;
        this.score = score;

        constituents = new QueryableList<>();
        relations = new QueryableList<>();

        startSpan = Integer.MAX_VALUE;
        endSpan = -1;

        this.tokensToConstituents = new ArrayList[this.getTextAnnotation().size()];
    }

    @SuppressWarnings("unchecked")
    private void addTokenToConstituentMapping(int tokenId, Constituent c) {

        if (this.tokensToConstituents[tokenId] == null) {
            this.tokensToConstituents[tokenId] = new ArrayList<>();
        }

        this.tokensToConstituents[tokenId].add(c);
    }

    private void removeTokenFromConstituentMapping(int tokenId, Constituent c) {

        if (this.tokensToConstituents[tokenId] == null) {
            this.tokensToConstituents[tokenId] = new ArrayList<>();
        }

        if (this.tokensToConstituents[tokenId].contains(c))
            this.tokensToConstituents[tokenId].remove(c);
    }

    private void removeAllTokenFromConstituentMapping(Constituent c) {

        for (int tokenId = 0; tokenId < this.tokensToConstituents.length; tokenId++) {
            removeTokenFromConstituentMapping(tokenId, c);
        }
    }

    /**
     * Adds a new constituent to this view
     *
     * @param constituent The new constituent to be added.
     */
    public void addConstituent(Constituent constituent) {
        if(!constituents.contains(constituent)) {
            constituents.add(constituent);

            startSpan = Math.min(this.startSpan, constituent.getStartSpan());
            endSpan = Math.max(this.endSpan, constituent.getEndSpan());

            if (startSpan >= 0 && endSpan >= 0) {
                for (int token = constituent.getStartSpan(); token < constituent.getEndSpan(); token++) {
                    this.addTokenToConstituentMapping(token, constituent);
                }
            }
        }else{
            System.err.println("Warning (View.java): not adding duplicate Constituent.");
        }
    }

    /**
     * Removes a constituent from this view. Removes any relations whose source or target was this constituent.
     */
    public void removeConstituent(Constituent constituent) {
        Set<Relation> relationsToRemove = new HashSet<>();
        for (Relation inRel : constituent.incomingRelations)
            relationsToRemove.add(inRel);
        for (Relation outRel : constituent.outgoingRelations)
            relationsToRemove.add(outRel);

        constituents.remove(constituent);
        removeAllTokenFromConstituentMapping(constituent);
        for (Relation rel : relationsToRemove)
            removeRelation(rel);
    }


    private void addRelatedConstituents(View restriction, Queue<Constituent> constituentsToConsider) {
        while (!constituentsToConsider.isEmpty()) {
            Constituent top = constituentsToConsider.remove();
            for (Relation r : top.getIncomingRelations()) {
                Constituent source = r.getSource();
                if (source.getStartSpan() < 0 && !restriction.containsConstituent(source)) {
                    restriction.addConstituent(source);
                    constituentsToConsider.add(source);
                }
            }
        }

        for (Relation r : this.relations) {
            if (restriction.containsConstituent(r.getSource())
                    && restriction.containsConstituent(r.getTarget())) {
                restriction.addRelation(r);
            }

        }
    }

    /**
     * This function is used by the filtering code. It adds all constituents that point to the
     * constituents in the parameter childrenToConsider and does this recursively.
     */
    private void addRelatedNullConstituents(Set<Constituent> output,
            Queue<Constituent> childrenToConsider) {
        while (!childrenToConsider.isEmpty()) {
            Constituent top = childrenToConsider.remove();

            for (Relation r : top.getIncomingRelations()) {
                Constituent source = r.getSource();
                if (source.getStartSpan() < 0 && !output.contains(source)) {
                    output.add(source);
                    childrenToConsider.add(source);
                }
            }

        }
    }

    /**
     * Adds a new relation to this view. This function throws an exception if the relation involves
     * constituents that are not contained in this view. In order for a relation to be added, the
     * constituents should have been added first. This function does not check for this.
     *
     * @param relation The relation to be added
     */
    public void addRelation(Relation relation) {

        // TODO checking is too slow!

        // if (!constituents.contains(relation.getSource()))
        // throw new IllegalArgumentException(
        // "Source constituent of relation is invalid");
        //
        // if (!constituents.contains(relation.getTarget()))
        // throw new IllegalArgumentException(
        // "Target constituent of relation is invalid");

        relations.add(relation);
    }

    /**
     * removes a relation from this view.
     */
    public void removeRelation(Relation relation) {
        relation.source.outgoingRelations.remove(relation);
        relation.target.incomingRelations.remove(relation);
        relations.remove(relation);
    }

    public void removeAllRelations() {
        List<Constituent> allCorefCons = this.getConstituents();
        for (Constituent c : allCorefCons) {
            for (Relation r : c.getIncomingRelations())
                this.removeRelation(r);
            for (Relation r : c.getOutgoingRelations())
                this.removeRelation(r);
            c.removeAllIncomingRelatons();
            c.removeAllOutgoingRelaton();
        }
    }

    public void removeAllConsituents() {
        constituents.clear();
        removeAllRelations();
    }

    /**
     * Removes all the attributes from the constituents of this of this view
     */
    public void removeAttributes() {
        for (Constituent cons : constituents)
            cons.removeAllAttributes();
    }

    /**
     * Checks if this view contains a constituent
     *
     * @param c The constituent, whose containership needs to be checked.
     * @return {@code true} if this view contains the constituent
     */
    public boolean containsConstituent(Constituent c) {
        return constituents.contains(c);
    }

    /**
     * This function creates a restriction of this view. That is, it creates a new view of the same
     * type as this view. This function does not add any constituents. That is done by
     * {@code View#getRestrictedView(Predicate,
     * ITransformer)}.
     */
    private View createRestrictedView(ITransformer<View, Double> scoreTransformer) {
        View restriction = null;

        Object[] args = {viewName, viewGenerator, textAnnotation, scoreTransformer.transform(this)};
        @SuppressWarnings("rawtypes")
        Class[] argsClass = {String.class, String.class, TextAnnotation.class, double.class};

        try {
            Constructor<? extends View> constructor = this.getClass().getConstructor(argsClass);

            restriction = constructor.newInstance(args);
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
        }
        return restriction;
    }

    /**
     * Returns the constituents contained in this view.
     *
     * @return the constituents
     */
    public List<Constituent> getConstituents() {
        return new ArrayList<>(constituents);
    }

    /**
     * Get the constituents which cover the input constituent {@code c}.
     *
     * @param c A constituent, not necessarily of this text annotation.
     * @return A list of constituents, which cover the same tokens as the input
     */
    public List<Constituent> getConstituentsCovering(Constituent c) {
        return this.getConstituentsCoveringSpan(c.getStartSpan(), c.getEndSpan());
    }

    /**
     * Get the labels of the constituents covered by the input constituent {@code c}
     *
     * @param c A constituent, not necessarily of this text annotation.
     * @return A list of labels of constituents, which cover the same tokens as the input
     */
    public List<String> getLabelsCovering(Constituent c) {
        return this.getLabelsCoveringSpan(c.getStartSpan(), c.getEndSpan());

    }

    public List<String> getLabelsCoveringSpan(int start, int end) {
        List<String> output = new ArrayList<>();

        for (int token = start; token < end; token++) {
            output.addAll(this.getLabelsCoveringToken(token));
        }

        return output;
    }

    public List<String> getLabelsCoveringTokens(Collection<Integer> tokenIds) {
        List<String> output = new ArrayList<>();

        for (int token : tokenIds) {
            output.addAll(this.getLabelsCoveringToken(token));
        }

        return output;
    }

    public List<String> getLabelsCoveringToken(int tokenId) {
        List<String> output = new ArrayList<>();

        if (tokenId < 0 || tokenId >= this.tokensToConstituents.length)
            return output;

        if (this.tokensToConstituents[tokenId] == null)
            return output;

        for (int i = 0; i < this.tokensToConstituents[tokenId].size(); i++) {

            Constituent c = (Constituent) this.tokensToConstituents[tokenId].get(i);
            output.add(c.getLabel());
        }

        return output;
    }

    public List<Constituent> getConstituentsCoveringSpan(int start, int end) {

        Set<Constituent> output = new HashSet<>();

        for (int token = start; token < end; token++) {
            output.addAll(getConstituentsCoveringToken(token));
        }

        List<Constituent> list = new ArrayList<>(output);

        Collections.sort(list, TextAnnotationUtilities.constituentStartComparator);
        return list;
    }

    /**
     * Given char-begin-offset and char-end-offset, it returns the constituents covering it.
     * @param charStart the begin char index
     * @param charEnd the end char index (one-past-the-end indexing)
     */
    public List<Constituent> getConstituentsCoveringCharSpan(int charStart, int charEnd) {

        Set<Constituent> output = new HashSet<>();
        for(Constituent c : this.constituents) {
            if(c.startCharOffset >= charStart && c.endCharOffset <= charEnd) output.add(c);
        }
        List<Constituent> list = new ArrayList<>(output);
        Collections.sort(list, TextAnnotationUtilities.constituentStartComparator);
        return list;
    }

    /**
     * Given char-begin-offset and char-end-offset, it returns the constituents with some overlap with the input char offsets.
     * @param charStart the begin char index
     * @param charEnd the end char index (one-past-the-end indexing)
     */
    public List<Constituent> getConstituentsOverlappingCharSpan(int charStart, int charEnd) {
        assert charStart <= charEnd: "The start offset should be smaller than the end offset";
        Set<Constituent> output = new HashSet<>();
        for(Constituent c : this.constituents) {
            if((c.startCharOffset <= charStart && c.endCharOffset >= charStart) ||
                    (c.startCharOffset <= charEnd && c.endCharOffset >= charEnd) ||
                    (c.startCharOffset >= charStart && c.endCharOffset <= charEnd) ||
                    (c.startCharOffset <= charStart && c.endCharOffset >= charEnd)) output.add(c);
        }
        List<Constituent> list = new ArrayList<>(output);
        Collections.sort(list, TextAnnotationUtilities.constituentStartComparator);
        return list;
    }

    /**
     * @return all the constituents that have exact span start/end.
     */
    public List<Constituent> getConstituentsWithSpan(IntPair span) {
        List<Constituent> list = new ArrayList<>();
        for(Constituent c : this.constituents) if (c.getSpan().equals(span)) list.add(c);
        return list;
    }

    public List<Constituent> getConstituentsCoveringTokens(Collection<Integer> tokenIds) {

        Set<Constituent> output = new HashSet<>();

        for (int token : tokenIds) {
            output.addAll(getConstituentsCoveringToken(token));
        }
        List<Constituent> list = new ArrayList<>(output);

        Collections.sort(list, TextAnnotationUtilities.constituentStartComparator);
        return list;

    }

    @SuppressWarnings("unchecked")
    public List<Constituent> getConstituentsCoveringToken(int tokenId) {

        if (tokenId < 0 || tokenId >= this.tokensToConstituents.length)
            return new ArrayList<>();

        if (this.tokensToConstituents[tokenId] == null)
            return new ArrayList<>();

        Queue<Constituent> childrenToConsider = new LinkedList<>();
        Set<Constituent> output = new HashSet<>();

        output.addAll(this.tokensToConstituents[tokenId]);

        childrenToConsider.addAll(output);

        addRelatedNullConstituents(output, childrenToConsider);

        return new ArrayList<>(output);
    }

    public int getEndSpan() {
        return this.endSpan;
    }

    protected Set<Constituent> getFilteredConstituents(Predicate<Constituent> predicate) {
        Set<Constituent> output = new HashSet<>();

        Queue<Constituent> childrenToConsider = new LinkedList<>();

        for (Constituent c : this.constituents) {
            if (predicate.transform(c)) {
                output.add(c);
                childrenToConsider.add(c);
            }
        }

        addRelatedNullConstituents(output, childrenToConsider);

        return output;
    }

    public int getNumberOfConstituents() {
        return constituents.size();
    }

    /**
     * @return the relations
     */
    public List<Relation> getRelations() {
        return new ArrayList<>(relations);
    }

    public View getRestrictedView(Predicate<Constituent> constituentPredicate,
            ITransformer<View, Double> scoreTransformer) {
        View restriction = createRestrictedView(scoreTransformer);

        if (restriction == null)
            return null;

        Queue<Constituent> constituentsToConsider = new LinkedList<>();
        for (Constituent c : getFilteredConstituents(constituentPredicate)) {
            restriction.addConstituent(c);
            constituentsToConsider.add(c);
        }

        addRelatedConstituents(restriction, constituentsToConsider);

        return restriction;

    }

    /**
     * @return the score
     */
    public double getScore() {
        return score;
    }

    public int getStartSpan() {
        return this.startSpan;
    }

    /**
     * @return the textAnnotation
     */
    public TextAnnotation getTextAnnotation() {
        return textAnnotation;
    }

    public View getViewCoveringSpan(int start, int end, ITransformer<View, Double> scoreTransformer) {
        View restriction = createRestrictedView(scoreTransformer);

        if (restriction == null)
            return null;

        Queue<Constituent> constituentsToConsider = new LinkedList<>();
        for (Constituent c : getConstituentsCoveringSpan(start, end)) {
            restriction.addConstituent(c);
            constituentsToConsider.add(c);
        }

        addRelatedConstituents(restriction, constituentsToConsider);

        // logger.info(restriction);

        return restriction;
    }

    public View getViewCoveringToken(int token, ITransformer<View, Double> scoreTransformer) {
        return getViewCoveringSpan(token, token + 1, scoreTransformer);
    }

    public View getViewCoveringTokens(Collection<Integer> tokens,
            ITransformer<View, Double> scoreTransformer) {
        View restriction = createRestrictedView(scoreTransformer);

        if (restriction == null)
            return null;

        Queue<Constituent> constituentsToConsider = new LinkedList<>();
        for (Constituent c : getConstituentsCoveringTokens(tokens)) {
            restriction.addConstituent(c);
            constituentsToConsider.add(c);
        }

        addRelatedConstituents(restriction, constituentsToConsider);

        return restriction;
    }

    /**
     * @return The name of this view. While this can be any arbitrary string, if the view represents
     *         a standard annotation, it is best to use one of the standard names from
     *         {@link edu.illinois.cs.cogcomp.core.datastructures.ViewNames}.
     */
    public String getViewName() {
        return this.viewName;
    }

    public String getViewGenerator() {
        return this.viewGenerator;
    }

    public IQueryable<Constituent> orderBy(Comparator<Constituent> comparator) {
        return this.constituents.orderBy(comparator);
    }

    public <S> IQueryable<S> select(ITransformer<Constituent, S> transformer) {
        return this.constituents.select(transformer);
    }

    public IQueryable<Constituent> unique() {
        return this.constituents.unique();
    }

    public IQueryable<Constituent> where(Predicate<Constituent> condition) {
        return this.constituents.where(condition);
    }

    public Iterator<Constituent> iterator() {
        return this.constituents.iterator();
    }

    @Override
    public int count() {
        return this.constituents.size();
    }
}
