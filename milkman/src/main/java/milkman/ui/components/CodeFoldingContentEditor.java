package milkman.ui.components;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;
import java.util.function.IntFunction;

import javafx.application.Platform;
import javafx.scene.control.Tooltip;
import milkman.PlatformUtil;
import org.apache.commons.lang3.StringUtils;
import org.fxmisc.richtext.LineNumberFactory;

import com.jfoenix.controls.JFXButton;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import lombok.Data;
import milkman.utils.Stopwatch;
import org.fxmisc.richtext.model.StyledDocument;

/**
 * this editor supports code folding
 * <p>
 * this should only be used for non-editable code Areas as it modifies the text and this might
 * result in unwanted changes to bound values
 */
public class CodeFoldingContentEditor extends ContentEditor {
    private ContentRange rootRange;

    private Button collapseAll;
    private Button expandAll;

    private Button collapseOne;
    private Button expandOne;

    private Button softWrap;

    private int currentFoldingLevel;
    private int maxFoldingLevel;

    private int minFoldingLevel = 0;

    private String originalText = "";
    private FoldOperatorFactory foldOperatorFactory;


    public CodeFoldingContentEditor() {

        collapseAll = new JFXButton();
        collapseAll.setTooltip(new Tooltip("collapse all levels"));
        collapseAll.setGraphic(new FontAwesomeIconView(FontAwesomeIcon.COMPRESS));
        collapseAll.setOnAction(e -> {
            setCollapseRecursively(rootRange, minFoldingLevel, 0);
            currentFoldingLevel = 0;
            redrawText();
        });


        expandAll = new JFXButton();
        expandAll.setTooltip(new Tooltip("expand all levels"));
        expandAll.setGraphic(new FontAwesomeIconView(FontAwesomeIcon.EXPAND));
        expandAll.setOnAction(e -> {
            setCollapseRecursively(rootRange, maxFoldingLevel, 0);
            currentFoldingLevel = maxFoldingLevel;
            redrawText();
        });

        collapseOne = new JFXButton();
        collapseOne.setTooltip(new Tooltip("collapse one level"));
        collapseOne.setGraphic(new FontAwesomeIconView(FontAwesomeIcon.PLUS));
        collapseOne.setOnAction(e -> {
            int nextLevel = Math.min(currentFoldingLevel + 1, maxFoldingLevel);
            setCollapseRecursively(rootRange, nextLevel, 0);
            currentFoldingLevel = nextLevel;
            redrawText();
        });


        expandOne = new JFXButton();
        expandOne.setTooltip(new Tooltip("Expand one level"));
        expandOne.setGraphic(new FontAwesomeIconView(FontAwesomeIcon.MINUS));
        expandOne.setOnAction(e -> {
            int nextLevel = Math.max(currentFoldingLevel - 1, minFoldingLevel);
            setCollapseRecursively(rootRange, nextLevel, 0);
            currentFoldingLevel = nextLevel;
            redrawText();
        });


        softWrap = new JFXButton();
        softWrap.setGraphic(new FontAwesomeIconView(FontAwesomeIcon.INDENT));
        softWrap.setTooltip(new Tooltip("Toggle Soft Wrap"));
        softWrap.setOnAction(e -> {
            codeArea.setWrapText(!codeArea.isWrapText());
            redrawText();
            // workaround https://github.com/FXMisc/RichTextFX/issues/979
            if ( codeArea.isWrapText() ) // brute force refresh :(
            {
                final int c = codeArea.getCaretPosition();
                final int p = codeArea.firstVisibleParToAllParIndex();
                final StyledDocument doc = codeArea.subDocument( 0, codeArea.getLength() );

                codeArea.clear();

                codeArea.insert( 0, doc );
                codeArea.showParagraphAtTop( p );
                codeArea.moveTo( c );
            }
        });

        header.getChildren().add(collapseAll);
        header.getChildren().add(expandAll);
        header.getChildren().add(collapseOne);
        header.getChildren().add(expandOne);
        header.getChildren().add(softWrap);

        highlighters.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null) {
                collapseAll.setVisible(n.supportFolding());
                expandAll.setVisible(n.supportFolding());
                collapseOne.setVisible(n.supportFolding());
                expandOne.setVisible(n.supportFolding());
                //trigger redraw bc of folding
                replaceText(originalText);
            }
        });

    }


    private void setCollapseRecursively(ContentRange node, int collapseAllBelowLevel, int curLevel) {
        if (node == null)
            return;

        if (node instanceof CollapsableRange) {
            CollapsableRange collapsable = (CollapsableRange) node;
            if (!collapsable.isRoot())
                collapsable.setCollapsed(curLevel > collapseAllBelowLevel);

            for (ContentRange child : collapsable.getChildren()) {
                setCollapseRecursively(child, collapseAllBelowLevel, curLevel + 1);
            }
        }
    }

    @Override
    protected void setupParagraphGraphics() {
        IntFunction<Node> numberFactory = LineNumberFactory.get(codeArea);
        if (foldOperatorFactory == null)
            foldOperatorFactory = new FoldOperatorFactory();
        IntFunction<Node> graphicFactory = line -> {
            var collapseSign = foldOperatorFactory.apply(line);

            Label lineNo = (Label) numberFactory.apply(line);
            HBox hbox = new HBox(lineNo);
            // monospace font, small hack, we assume 10px character width (not totally accurate, but good enough). +10 insets, +20 for our collapser
            hbox.setPrefWidth(lineNo.getText().length() * 10 + 10 + 20);
            if (collapseSign != null)
                hbox.getChildren().add(collapseSign);

            hbox.setAlignment(Pos.CENTER_LEFT);
            return hbox;
        };
        codeArea.setParagraphGraphicFactory(graphicFactory);
    }


    protected void replaceText(String text) {
    	Stopwatch.start("folding");
        originalText = text;
        if (getCurrentContenttypePlugin() != null && getCurrentContenttypePlugin().supportFolding()) {
            rootRange = getCurrentContenttypePlugin().computeFolding(text);
            Stopwatch.logTime("folding", "compute folding");
            maxFoldingLevel = computeMaxFoldingLevel(rootRange);
            Stopwatch.logTime("folding", "compute max folding lvl");
            currentFoldingLevel = maxFoldingLevel; //start with expanded tree
            redrawText();
            Stopwatch.logTime("folding", "redraw text");
        } else {
        	foldOperatorFactory.clear();
            super.replaceText(text);
        }
        Stopwatch.stop("folding");
    }

    private int computeMaxFoldingLevel(ContentRange node) {
        if (node instanceof CollapsableRange) {
            CollapsableRange collapsable = (CollapsableRange) node;


            int childLvl = collapsable.getChildren()
                    .stream()
                    .mapToInt(this::computeMaxFoldingLevel)
                    .max().orElse(0);

            return childLvl + (collapsable.isRoot() ? 0 : 1); //dont count in root
        }

        return 0;
    }


    private void redrawText() {
    	Stopwatch.start("redraw");
        foldOperatorFactory.updateLineToRangeCache(rootRange);
        Stopwatch.logTime("redraw", "range cache update");
        
        int caretPos = 0;
        if (codeArea.getText() != null
            && codeArea.getText().length() > 0
            && codeArea.getWidth() > 0
            && !shouldSkipExpensiveOperations(codeArea.getText())) {
            //hit test seems to be expensive
            caretPos = codeArea.hit(50, 10).getInsertionIndex();
            Stopwatch.logTime("redraw", "hit test");
        }

        StringBuilder b = new StringBuilder();
        rootRange.appendToString(b);
        Stopwatch.logTime("redraw", "build content");
        String replacement = b.toString();
        codeArea.replaceText(replacement);
        Stopwatch.logTime("redraw", "replace content in editor");
        //reset window scroll to previous position
        codeArea.moveTo(caretPos);
        codeArea.requestFollowCaret();
        Stopwatch.stop("redraw");
    }


    public void formatCurrentCode() {
//    	System.out.println("### Formatting");
        if (getCurrentContenttypePlugin() != null && getCurrentContenttypePlugin().supportFormatting()) {
            replaceText(formatCode(originalText));
        }
    }
    
    @Override
    public void addContent(String additiveContent) {
    	originalText += additiveContent;
//        System.out.println("Content length: " + originalText.length());
    	super.addContent(additiveContent);
    };

    private class FoldOperatorFactory implements IntFunction<Node> {

        Map<Integer, CollapsableRange> lineToContentLookup = new HashMap<>();

        public void updateLineToRangeCache(ContentRange node) {
            clear();
            updateLineToRangeCacheInternal(node, 0);
        }

        public void clear() {
            lineToContentLookup.clear();
		}

		private void updateLineToRangeCacheInternal(ContentRange node, int lineOffset) {
            if (node == null)
                return;

            if (node instanceof CollapsableRange) {
                CollapsableRange collapsable = (CollapsableRange) node;
                if (!collapsable.isRoot()) {
                    lineToContentLookup.put(lineOffset, collapsable);
                }

                if (!collapsable.isCollapsed()) {
                	int childLineOffset = lineOffset;
                    for (ContentRange child : collapsable.getChildren()) {
                        updateLineToRangeCacheInternal(child, childLineOffset);
                        childLineOffset += child.getContainedLines();
                    }
                }
            }
        }

        private Optional<CollapsableRange> lookupCollapsableRangeInStartLineIdx(int lineNumber) {
            return Optional.ofNullable(lineToContentLookup.get(lineNumber));
        }

        @Override
        public Node apply(int lineNumber) {
            return lookupCollapsableRangeInStartLineIdx(lineNumber)
                    .map(r -> {
                        FontAwesomeIconView view;
                        if (r.isCollapsed()) {
                            view = new FontAwesomeIconView(FontAwesomeIcon.PLUS_SQUARE);
                        } else {
                            view = new FontAwesomeIconView(FontAwesomeIcon.MINUS_SQUARE);
                        }
                        view.setOnMouseClicked(e -> {
                            r.setCollapsed(!r.isCollapsed(), e.getButton() == MouseButton.SECONDARY);
                            redrawText();
                        });
                        view.setStyleClass("handCursor");
                        return view;
                    }).orElse(null);
        }
    }

    @Data
    public abstract static class ContentRange {
        protected final ContentRange prevRange;

        public abstract int getContainedLines();

        public abstract void appendToString(StringBuilder b);

    }

    @Data
    public static class TextRange extends ContentRange {
        private final String text;
        private final int containedLines;

        public TextRange(ContentRange prevRange, String text) {
            super(prevRange);
            this.text = text;
            containedLines = StringUtils.countMatches(text, '\n');
        }

        public void appendToString(StringBuilder b) {
            b.append(text);
        }
    }

    @Data
    public static class CollapsableRange extends ContentRange {
        private final boolean isRoot;
        private final int collapsedLines;
        private String collapsedText;
        private boolean collapsed;

        private List<ContentRange> children = new LinkedList<>();

        public CollapsableRange(ContentRange prevRange, boolean isRoot, String collapsedText) {
            super(prevRange);
            this.isRoot = isRoot;
            this.collapsedText = collapsedText;
            this.collapsedLines = StringUtils.countMatches(collapsedText, '\n');
        }

        public void setCollapsed(boolean value, boolean applyRecursive) {
            collapsed = value;
            if (applyRecursive) {
            	for (ContentRange child : children) {
					if (child instanceof CollapsableRange)
						((CollapsableRange) child).setCollapsed(value, true);
				}
            }
        }

        public void addChildren(ContentRange range) {
            children.add(range);
        }

        public void appendToString(StringBuilder b) {
            if (collapsed) {
                b.append(collapsedText);
            } else {
                for (ContentRange range : children) {
                    range.appendToString(b);
                }
            }
        }

        public int getContainedLines() {
            if (collapsed)
                return collapsedLines;
            int sum = 0;
            for (ContentRange range : children) {
                sum += range.getContainedLines();
            }
            return sum;
        }

//        public int getStartLine() {
//            return isRoot ? -1 : super.getStartLine();
//        }

    }

    public static class CodeFoldingBuilder {
        private final String text;
        private final Stack<CollapsableRange> rangeStack;
        private int curIdx = 0;

        public CodeFoldingBuilder(String text) {
            this.text = text;
            rangeStack = new Stack<>();
            rangeStack.add(new CollapsableRange(null, true, ""));
        }

        /**
         * will add everything from current idx to given idx as text node and add a new collapsable to the stack
         *
         * @param nextIdx
         */
        public void startRange(int nextIdx, String placeholder) {
            ContentRange prev = addLeftOverTextToCurrentRange(nextIdx);

            CollapsableRange newCollapsable = new CollapsableRange(prev, false, placeholder);
            rangeStack.peek().addChildren(newCollapsable);
            rangeStack.add(newCollapsable);
        }

        private ContentRange addLeftOverTextToCurrentRange(int nextIdx) {
            ContentRange prev = null;
            if (rangeStack.peek().getChildren().size() > 0)
                prev = rangeStack.peek().getChildren().get(rangeStack.peek().getChildren().size() - 1);

            if (prev == null && rangeStack.size() > 1) { // might be the first in this collapsable. if so, link to previous collapsable
                var grandParent = rangeStack.elementAt(rangeStack.size() - 2);
                if (grandParent.getChildren().size() > 1) {
                    //get former sibling
                    prev = grandParent.getChildren().get(grandParent.getChildren().size() - 2);
                }
            }

            if (curIdx < nextIdx) {
                prev = new TextRange(prev, text.substring(curIdx, nextIdx));
                rangeStack.peek().addChildren(prev);
            }
            curIdx = nextIdx;
            return prev;
        }

        /**
         * will close the current collapsable and pop it from stack
         *
         * @param nextIdx
         */
        public void endRange(int nextIdx) {
            ContentRange prev = addLeftOverTextToCurrentRange(nextIdx);

            //just as a guard, dont pop the root
            if (rangeStack.size() > 1)
                rangeStack.pop();
        }

        public CollapsableRange build() {
            ContentRange prev = addLeftOverTextToCurrentRange(text.length());
            return rangeStack.get(0);
        }

    }
}
