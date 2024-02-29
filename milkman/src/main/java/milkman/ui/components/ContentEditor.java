package milkman.ui.components;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane.ScrollBarPolicy;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyCombination.Modifier;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import lombok.Getter;
import lombok.val;
import milkman.PlatformUtil;
import milkman.ui.main.options.CoreApplicationOptionsProvider;
import milkman.ui.plugin.ContentTypePlugin;
import milkman.utils.ContentTypeMatcher;
import milkman.utils.Stopwatch;
import milkman.utils.StringUtils;
import milkman.utils.fxml.GenericBinding;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.Caret;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.fxmisc.wellbehaved.event.EventPattern;
import org.fxmisc.wellbehaved.event.InputMap;
import org.fxmisc.wellbehaved.event.Nodes;
import org.reactfx.EventStream;
import org.reactfx.EventStreams;
import org.reactfx.Subscription;

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static javafx.scene.input.KeyCombination.SHIFT_DOWN;

/**
 * @author peter
 *
 */
public class ContentEditor extends VBox {

	private static final String DEFAULT_CONTENTTYPE = "text/plain";

	private static final ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactory() {
		@Override
		public Thread newThread(Runnable r) {
			Thread t = Executors.defaultThreadFactory().newThread(r);
			t.setDaemon(true);
			return t;
		}
	});

	@Getter
	protected CodeArea codeArea;

	private GenericBinding<Object, String> contentBinding;

	protected JFXComboBox<ContentTypePlugin> highlighters;

	private JFXButton format;

	protected HBox header;

	private SearchBox search;
	private ContentSearch contentSearch;

	protected final VirtualizedScrollPane scrollPane;

	public ContentEditor() {
		getStyleClass().add("contentEditor");

		setupHeader();
		setupCodeArea();
		setupSearch();

		StackPane.setAlignment(search, Pos.TOP_RIGHT);
		// bug: scrollPane has some issue if it is rendered within a tab that
		// is not yet shown with content that needs a scrollbar
		// this leads to e.g. tabs not being updated, if triggered programmatically
		// switching to ALWAYS for scrollbars fixes this issue
		scrollPane = new VirtualizedScrollPane(codeArea, ScrollBarPolicy.ALWAYS,
				ScrollBarPolicy.ALWAYS);

		StackPane contentPane = new StackPane(scrollPane, search);
		VBox.setVgrow(contentPane, Priority.ALWAYS);

		getChildren().add(contentPane);
	}

	private void setupCodeArea() {
		codeArea = new CodeArea();
		//always show caret, even for non-editable
		codeArea.setShowCaret(Caret.CaretVisibility.ON);
//		codeArea.setWrapText(true);
		setupParagraphGraphics();
		EventStream<Object> highLightTrigger = EventStreams.merge(codeArea.multiPlainChanges(),
				EventStreams.changesOf(highlighters.getSelectionModel().selectedItemProperty()),
				EventStreams.eventsOf(format, MouseEvent.MOUSE_CLICKED));


		//behavior of TAB: 2 spaces, allow outdention via SHIFT-TAB, if cursor is at beginning
		Nodes.addInputMap(codeArea, InputMap.consume(
				EventPattern.keyPressed(KeyCode.TAB),
				e -> codeArea.replaceSelection("  ")
		));

		Nodes.addInputMap(codeArea, InputMap.consume(
				EventPattern.keyPressed(KeyCode.TAB, SHIFT_DOWN),
				e -> {
					var paragraph = codeArea.getParagraph(codeArea.getCurrentParagraph());
					var indentation = StringUtils.countStartSpaces(paragraph.getText());

					//is the cursor in the white spaces
					if (codeArea.getCaretColumn() <= indentation){
						var charsToRemove = Math.min(indentation, 2);

						codeArea.replaceText(new IndexRange(codeArea.getAbsolutePosition(codeArea.getCurrentParagraph(), 0),
								codeArea.getAbsolutePosition(codeArea.getCurrentParagraph(), (int) charsToRemove)),
								"");
					}
				}
		));

		// sync highlighting:
//		Subscription cleanupWhenNoLongerNeedIt = highLightTrigger
//				 .successionEnds(Duration.ofMillis(500))
//				 .subscribe(ignore -> {
//					System.out.println("Triggered highlight via end-of-succession");
//					 highlightCode();
//				 });

		// async highlighting:
		Subscription cleanupWhenNoLongerNeedIt = highLightTrigger.successionEnds(Duration.ofMillis(500))
				.supplyTask(this::highlightCodeAsync).awaitLatest(codeArea.multiPlainChanges()).filterMap(t -> {
					if (t.isSuccess()) {
						return Optional.of(t.get());
					} else {
						t.getFailure().printStackTrace();
						return Optional.empty();
					}
				}).subscribe(this::applyHighlighting);

		Modifier controlKey = KeyCombination.CONTROL_DOWN;
		if (SystemUtils.IS_OS_MAC){
			controlKey = KeyCombination.META_DOWN;
		}
		val keyCombination = PlatformUtil.getControlKeyCombination(KeyCode.F);
		codeArea.setOnKeyPressed(e -> {
			if (keyCombination.match(e)) {
				focusSearch();
			}
		});
	}

	protected void setupParagraphGraphics() {
		codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
	}

	private void setupSearch() {
		search = new SearchBox();
		contentSearch = new ContentSearch(codeArea);
		search.onSearch((text, forward) -> {
			if (forward){
				contentSearch.moveToNextMatch(text);
			} else {
				contentSearch.moveToPrevMatch(text);
			}
		});
		search.onCloseRequest(this::hideSearch);
	}

	private void hideSearch() {
		search.setVisible(false);
		codeArea.requestFocus();
	}

	private void focusSearch() {
		search.setVisible(true);
		search.requestFocus();
	}

	private void setupHeader() {
		highlighters = new JFXComboBox<ContentTypePlugin>();
		highlighters.setConverter(new StringConverter<ContentTypePlugin>() {
			@Override
			public String toString(ContentTypePlugin object) {
				return object.getName();
			}

			@Override
			public ContentTypePlugin fromString(String string) {
				return null;
			}
		});

		highlighters.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
			if (n != null)
				format.setVisible(n.supportFormatting());
		});

		format = new JFXButton("Format");
		format.setVisible(false);

		format.setOnAction(e -> formatCurrentCode());

		header = new HBox(new Label("Content Type:"), highlighters, format);
		header.getStyleClass().add("contentEditor-header");

		getChildren().add(header);
	}

	public void addExtraHeaderElement(Node node, boolean atBeginning) {
		if (atBeginning) {
			header.getChildren().add(0, node);
		} else {
			header.getChildren().add(node);
		}
	}

	public void setHeaderVisibility(boolean isVisible) {
		if (isVisible && !getChildren().contains(header)) {
			getChildren().add(0, header);
		} else {
			getChildren().remove(header);
		}
	}

	private Task<StyleSpans<Collection<String>>> highlightCodeAsync() {
		String text = codeArea.getText();
		Task<StyleSpans<Collection<String>>> task = new Task<StyleSpans<Collection<String>>>() {
			@Override
			protected StyleSpans<Collection<String>> call() throws Exception {
				return computeHighlighting(text);
			}
		};
		executor.execute(task);
		return task;
	}

	private void applyHighlighting(StyleSpans<Collection<String>> highlighting) {
		codeArea.setStyleSpans(0, highlighting);
	}

	public void formatCurrentCode() {
		StopWatch s = new StopWatch();
		s.start();
		try {
			if (getCurrentContenttypePlugin() != null && getCurrentContenttypePlugin().supportFormatting()) {
				codeArea.replaceText(formatCode(codeArea.getText()));
			}
		} finally {
			s.stop();
//			System.out.println("Formatting code and replace: " + s.getTime() + " ms");
		}
	}

	protected String formatCode(String code) {
		if (code == null || code.equals(""))
			return "";

		StopWatch s = new StopWatch();
		s.start();
		try {
			if (getCurrentContenttypePlugin() != null && getCurrentContenttypePlugin().supportFormatting()) {
				return getCurrentContenttypePlugin().formatContent(code);
			} else {
				return code;
			}
		} finally {
			s.stop();
//			System.out.println("Formatting code: " + s.getTime() + " ms");
		}
	}

	protected ContentTypePlugin getCurrentContenttypePlugin() {
		return highlighters.getValue();
	}

	private StyleSpans<Collection<String>> computeHighlighting(String text) {
		StopWatch s = new StopWatch();
		s.start();
		try {
			if (getCurrentContenttypePlugin() != null && !shouldSkipExpensiveOperations(text))
				return getCurrentContenttypePlugin().computeHighlighting(text);
			else
				return noHighlight(text);
		} finally {
			s.stop();
//			System.out.println("Highlighting code: " + s.getTime() + " ms");
		}
	}

	/**
	 * Because Flowless cannot handle syntax highlighting of very long lines that well,
	 * we just disable highlighting, if text contains very long lines
	 *
	 * @param text
	 * @return
	 */
	protected boolean shouldSkipExpensiveOperations(String text) {
		/**
		 * iterates over the string, counting the chars until next \n thereby.
		 * If line is above max, it returns true
		 */

		StringCharacterIterator iterator = new StringCharacterIterator(text);
		long curLineLength = 0;
		while(true) {
			char c = iterator.next();
			if (c == CharacterIterator.DONE) {
				break;
			}
			curLineLength++;
			if (c == '\n') {
				if (curLineLength > 1_000) {
					return true;
				}
				curLineLength = 0;
			}

		}
		return curLineLength > 1_000;
	}

	private StyleSpans<Collection<String>> noHighlight(String text) {
		val b = new StyleSpansBuilder<Collection<String>>();
		b.add(Collections.singleton("plain"), text.length());
		return b.create();
	}

	public void setEditable(boolean editable) {
		codeArea.setEditable(editable);
		if (editable){
			setupAutoIndentation();
		}
	}

	private void setupAutoIndentation() {
		codeArea.addEventFilter(KeyEvent.KEY_PRESSED, ke -> {
			if (ke.getCode() == KeyCode.ENTER){
				String currentLine = codeArea.getParagraph( codeArea.getCurrentParagraph()).getText();
				if (getCurrentContenttypePlugin() != null) {
					Platform.runLater( () -> codeArea.insertText( codeArea.getCaretPosition(), getCurrentContenttypePlugin().computeIndentationForNextLine(currentLine) ) );
				}
			}
		});
	}

	public void setContentTypePlugins(List<ContentTypePlugin> plugins) {
		highlighters.getItems().addAll(plugins);
		// set plain highlighter as default:
		String contentType = DEFAULT_CONTENTTYPE;
		setActiveContentType(plugins, contentType);
	}

	private void setActiveContentType(List<ContentTypePlugin> plugins, String contentType) {
		plugins.stream()
			.filter(p -> ContentTypeMatcher.matches(p.getContentType(), contentType))
			.findAny().ifPresent(t -> {
				format.setVisible(t.supportFormatting());
				//System.out.println("Setting active highlighter: " + t);
				highlighters.setValue(t);
				//System.out.println("End Setting active highlighter");
			});
	}

	public void setContentType(String contentType) {
		var stopwatchId = "ContentType:"+contentType;
		Stopwatch.start(stopwatchId);
		setActiveContentType(highlighters.getItems(), contentType);
		Stopwatch.logTime(stopwatchId, "changed highlighter");
		if (CoreApplicationOptionsProvider.options().isAutoformatContent())
			formatCurrentCode();
		Stopwatch.stop(stopwatchId);
	}

	public void setContent(Supplier<String> getter, Consumer<String> setter) {
//		if (contentBinding != null) {
//			Bindings.unbindBidirectional(codeAreaTextBinding, contentBinding);
//		}
//		contentBinding = GenericBinding.of(o -> getter.get(), (o,v) -> setter.accept(v), null);
//		codeAreaTextBinding = Var.mapBidirectional(contentBinding,  s -> s, s->s);

		String curValue = getter.get();
		if (CoreApplicationOptionsProvider.options().isAutoformatContent())
			curValue = formatCode(curValue);

		replaceText(curValue);

		codeArea.textProperty().addListener((obs, o, n) -> {
			if (codeArea.isEditable()) {
				setter.accept(n);
			}
		});
	}

	public void addContent(String additiveContent) {
//		if (contentBinding != null) {
//			Bindings.unbindBidirectional(codeAreaTextBinding, contentBinding);
//		}
//		contentBinding = GenericBinding.of(o -> getter.get(), (o,v) -> setter.accept(v), null);
//		codeAreaTextBinding = Var.mapBidirectional(contentBinding,  s -> s, s->s);

		codeArea.appendText(additiveContent);
	}

	protected void replaceText(String newText) {
		codeArea.replaceText(newText != null ? newText : "");
	}

	public void setDisableContent(Boolean disable) {
		if (disable)
			codeArea.getStyleClass().add("disabled");
		else
			codeArea.getStyleClass().remove("disabled");
		codeArea.setDisable(disable);
	}

}
