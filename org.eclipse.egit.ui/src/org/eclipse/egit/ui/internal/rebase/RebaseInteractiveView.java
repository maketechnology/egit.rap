/*******************************************************************************
 * Copyright (c) 2013, 2015 SAP AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Tobias Pfeifer (SAP AG) - initial implementation
 *    Tobias Baumann (tobbaumann@gmail.com) - Bug 473950
 *    Thomas Wolf <thomas.wolf@paranor.ch> - Bug 477248
 *******************************************************************************/
package org.eclipse.egit.ui.internal.rebase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.egit.core.AdapterUtils;
import org.eclipse.egit.core.RepositoryUtil;
import org.eclipse.egit.core.internal.rebase.RebaseInteractivePlan;
import org.eclipse.egit.core.internal.rebase.RebaseInteractivePlan.ElementAction;
import org.eclipse.egit.core.internal.rebase.RebaseInteractivePlan.ElementType;
import org.eclipse.egit.core.internal.rebase.RebaseInteractivePlan.PlanElement;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.egit.ui.Activator;
import org.eclipse.egit.ui.UIPreferences;
import org.eclipse.egit.ui.UIUtils;
import org.eclipse.egit.ui.internal.CommonUtils;
import org.eclipse.egit.ui.internal.PreferenceBasedDateFormatter;
import org.eclipse.egit.ui.internal.UIIcons;
import org.eclipse.egit.ui.internal.UIText;
import org.eclipse.egit.ui.internal.actions.BooleanPrefAction;
import org.eclipse.egit.ui.internal.commands.shared.AbortRebaseCommand;
import org.eclipse.egit.ui.internal.commands.shared.AbstractRebaseCommandHandler;
import org.eclipse.egit.ui.internal.commands.shared.ContinueRebaseCommand;
import org.eclipse.egit.ui.internal.commands.shared.ProcessStepsRebaseCommand;
import org.eclipse.egit.ui.internal.commands.shared.SkipRebaseCommand;
import org.eclipse.egit.ui.internal.commit.CommitEditor;
import org.eclipse.egit.ui.internal.commit.RepositoryCommit;
import org.eclipse.egit.ui.internal.repository.RepositoriesView;
import org.eclipse.egit.ui.internal.repository.tree.RepositoryTreeNode;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.TreeColumnLayout;
import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.window.ToolTip;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.GitDateFormatter;
import org.eclipse.jgit.util.GitDateFormatter.Format;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.widgets.ExpandableComposite;
import org.eclipse.ui.forms.widgets.Form;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.forms.widgets.Section;
import org.eclipse.ui.part.ViewPart;

/**
 * View visualizing git interactive rebase
 */
public class RebaseInteractiveView extends ViewPart implements
		RebaseInteractivePlan.RebaseInteractivePlanChangeListener {

	/**
	 * interactive rebase view id
	 */
	public static final String VIEW_ID = "org.eclipse.egit.ui.InteractiveRebaseView"; //$NON-NLS-1$

	TreeViewer planTreeViewer;

	private PlanLayout planLayout;

	private RebaseInteractivePlan currentPlan;

	private Repository currentRepository;

	private RebaseInteractiveStepActionToolBarProvider actionToolBarProvider;

	private ToolItem startItem;

	private ToolItem abortItem;

	private ToolItem skipItem;

	private ToolItem continueItem;

	private ToolItem refreshItem;

	private boolean listenOnRepositoryViewSelection = true;

	private ISelectionListener selectionChangedListener;

	private boolean dndEnabled = false;

	private Form form;

	private LocalResourceManager resources = new LocalResourceManager(
			JFaceResources.getResources());

	private GitDateFormatter dateFormatter = getNewDateFormatter();

	/** these columns are dynamically resized to fit their contents */
	private TreeViewerColumn[] dynamicColumns;

	private List<PlanContextMenuAction> contextMenuItems;

	private RebasePlanIndexer planIndexer;

	private IPreferenceChangeListener prefListener;

	private IPropertyChangeListener uiPrefsListener;

	private InitialSelection initialSelection;

	@Override
	public void init(IViewSite site) throws PartInitException {
		super.init(site);
		setPartName(UIText.InteractiveRebaseView_this_partName);
		initInitialSelection(site);
	}

	private void initInitialSelection(IViewSite site) {
		this.initialSelection = new InitialSelection(
				site.getWorkbenchWindow().getSelectionService().getSelection());
		if (!isViewInputDerivableFromSelection(initialSelection.selection)) {
			this.initialSelection.activeEditor = site.getPage()
					.getActiveEditor();
		}
	}

	private static boolean isViewInputDerivableFromSelection(Object o) {
		return o instanceof StructuredSelection
				&& ((StructuredSelection) o).size() == 1;
	}

	/**
	 * Set the view input if the passed object can be used to determine the
	 * current repository
	 *
	 * @param o
	 */
	public void setInput(Object o) {
		if (o == null)
			return;

		if (isViewInputDerivableFromSelection(o)) {
			o = ((StructuredSelection) o).getFirstElement();
		}
		Repository repo = null;
		if (o instanceof RepositoryTreeNode<?>) {
			repo = ((RepositoryTreeNode) o).getRepository();
		} else if (o instanceof Repository) {
			repo = (Repository) o;
		} else {
			IResource resource = AdapterUtils.adapt(o, IResource.class);
			if (resource != null) {
				RepositoryMapping mapping = RepositoryMapping
						.getMapping(resource);
				if (mapping == null) {
					return;
				}
				repo = mapping.getRepository();
			}
		}
		if (repo == null) {
			repo = AdapterUtils.adapt(o, Repository.class);
		}

		currentRepository = repo;
		showRepository(repo);
	}

	/**
	 * @return {@link RebaseInteractiveView#currentPlan}
	 */
	public RebaseInteractivePlan getCurrentPlan() {
		return currentPlan;
	}

	@Override
	public void dispose() {
		removeListeners();
		resources.dispose();
		super.dispose();
	}

	private void removeListeners() {
		ISelectionService srv = CommonUtils.getService(getSite(), ISelectionService.class);
		srv.removePostSelectionListener(RepositoriesView.VIEW_ID,
				selectionChangedListener);
		if (currentPlan != null)
			currentPlan.removeRebaseInteractivePlanChangeListener(this);

		if (planIndexer != null)
			planIndexer.dispose();

		InstanceScope.INSTANCE.getNode(
				org.eclipse.egit.core.Activator.getPluginId())
				.removePreferenceChangeListener(prefListener);
		Activator.getDefault().getPreferenceStore()
				.removePropertyChangeListener(uiPrefsListener);
	}

	@Override
	public void createPartControl(Composite parent) {
		GridLayoutFactory.fillDefaults().applyTo(parent);
		final FormToolkit toolkit = new FormToolkit(parent.getDisplay());
		parent.addDisposeListener(new DisposeListener() {

			@Override
			public void widgetDisposed(DisposeEvent e) {
				toolkit.dispose();
			}
		});
		form = createForm(parent, toolkit);
		createCommandToolBar(form, toolkit);
		SashForm sashForm = createRebasePlanSashForm(form, toolkit);

		Section rebasePlanSection = toolkit.createSection(sashForm,
				ExpandableComposite.TITLE_BAR);
		planTreeViewer = createPlanTreeViewer(rebasePlanSection, toolkit);

		planLayout = new PlanLayout();
		planTreeViewer.getTree().getParent().setLayout(planLayout);

		createColumns(planLayout);
		createStepActionToolBar(rebasePlanSection, toolkit);
		createPopupMenu(planTreeViewer);

		setupListeners();
		createLocalDragandDrop();
		planTreeViewer.addDoubleClickListener(new IDoubleClickListener() {

			@Override
			public void doubleClick(DoubleClickEvent event) {
				PlanElement element = (PlanElement) ((IStructuredSelection) event
						.getSelection()).getFirstElement();
				if (element == null)
					return;

				RepositoryCommit commit = loadCommit(element.getCommit());
				if (commit != null)
					CommitEditor.openQuiet(commit);

			}

			private RepositoryCommit loadCommit(
					AbbreviatedObjectId abbreviatedObjectId) {
				if (abbreviatedObjectId != null) {
					try (RevWalk walk = new RevWalk(
							RebaseInteractiveView.this.currentRepository)) {
						Collection<ObjectId> resolved = walk.getObjectReader()
								.resolve(abbreviatedObjectId);
						if (resolved.size() == 1) {
							RevCommit commit = walk.parseCommit(resolved
									.iterator().next());
							return new RepositoryCommit(
									RebaseInteractiveView.this.currentRepository,
									commit);
						}
					} catch (IOException e) {
						return null;
					}
				}
				return null;
			}
		});

		prefListener = new IPreferenceChangeListener() {
			@Override
			public void preferenceChange(PreferenceChangeEvent event) {
				if (!RepositoryUtil.PREFS_DIRECTORIES_REL
						.equals(event.getKey())) {
					return;
				}

				final Repository repo = currentRepository;
				if (repo == null)
					return;

				if (Activator.getDefault().getRepositoryUtil().contains(repo))
					return;

				// Unselect repository as it has been removed
				Display.getDefault().asyncExec(new Runnable() {
					@Override
					public void run() {
						currentRepository = null;
						showRepository(null);
					}
				});
			}
		};

		InstanceScope.INSTANCE.getNode(
				org.eclipse.egit.core.Activator.getPluginId())
				.addPreferenceChangeListener(prefListener);

		uiPrefsListener = new IPropertyChangeListener() {
			@Override
			public void propertyChange(PropertyChangeEvent event) {
				String property = event.getProperty();
				if (UIPreferences.DATE_FORMAT.equals(property)
						|| UIPreferences.DATE_FORMAT_CHOICE.equals(property)
						|| UIPreferences.RESOURCEHISTORY_SHOW_RELATIVE_DATE
								.equals(property)) {
					refresh();
				}
			}
		};

		Activator.getDefault().getPreferenceStore()
				.addPropertyChangeListener(uiPrefsListener);

		IActionBars actionBars = getViewSite().getActionBars();
		IToolBarManager toolbar = actionBars.getToolBarManager();

		listenOnRepositoryViewSelection = RebaseInteractivePreferences
				.isReactOnSelection();

		// link with selection
		Action linkSelectionAction = new BooleanPrefAction(
				(IPersistentPreferenceStore) Activator.getDefault()
						.getPreferenceStore(),
				UIPreferences.REBASE_INTERACTIVE_SYNC_SELECTION,
				UIText.InteractiveRebaseView_LinkSelection) {
			@Override
			public void apply(boolean value) {
				listenOnRepositoryViewSelection = value;
			}
		};
		linkSelectionAction.setImageDescriptor(UIIcons.ELCL16_SYNCED);
		toolbar.add(linkSelectionAction);

		reactOnInitalSelection();
	}

	private void createCommandToolBar(Form theForm, FormToolkit toolkit) {
		ToolBar toolBar = new ToolBar(theForm.getHead(), SWT.FLAT);
		toolBar.setOrientation(SWT.RIGHT_TO_LEFT);
		theForm.setHeadClient(toolBar);

		toolkit.adapt(toolBar);
		toolkit.paintBordersFor(toolBar);

		startItem = new ToolItem(toolBar, SWT.NONE);
		startItem.setImage(UIIcons.getImage(resources,
				UIIcons.REBASE_PROCESS_STEPS));
		startItem.addSelectionListener(new RebaseCommandItemSelectionListener(
				new ProcessStepsRebaseCommand()));
		startItem.setEnabled(false);
		startItem.setText(UIText.InteractiveRebaseView_startItem_text);

		continueItem = new ToolItem(toolBar, SWT.NONE);
		continueItem.setImage(UIIcons.getImage(resources,
				UIIcons.REBASE_CONTINUE));
		continueItem
				.addSelectionListener(new RebaseCommandItemSelectionListener(
						new ContinueRebaseCommand()));
		continueItem.setEnabled(false);
		continueItem.setText(UIText.InteractiveRebaseView_continueItem_text);

		skipItem = new ToolItem(toolBar, SWT.NONE);
		skipItem.setImage(UIIcons.getImage(resources, UIIcons.REBASE_SKIP));
		skipItem.addSelectionListener(new RebaseCommandItemSelectionListener(
				new SkipRebaseCommand()));
		skipItem.setText(UIText.InteractiveRebaseView_skipItem_text);
		skipItem.setEnabled(false);

		abortItem = new ToolItem(toolBar, SWT.NONE);
		abortItem.setImage(UIIcons.getImage(resources, UIIcons.REBASE_ABORT));
		abortItem.addSelectionListener(new RebaseCommandItemSelectionListener(
				new AbortRebaseCommand()));
		abortItem.setText(UIText.InteractiveRebaseView_abortItem_text);
		abortItem.setEnabled(false);

		createSeparator(toolBar);

		refreshItem = new ToolItem(toolBar, SWT.NONE);
		refreshItem.setImage(UIIcons
				.getImage(resources, UIIcons.ELCL16_REFRESH));
		refreshItem.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				refresh();
			}
		});
		refreshItem.setText(UIText.InteractiveRebaseView_refreshItem_text);
	}

	private static ToolItem createSeparator(ToolBar toolBar) {
		return new ToolItem(toolBar, SWT.SEPARATOR);
	}

	private TreeViewer createPlanTreeViewer(Section rebasePlanSection,
			FormToolkit toolkit) {

		Composite rebasePlanTableComposite = toolkit
				.createComposite(rebasePlanSection);
		toolkit.paintBordersFor(rebasePlanTableComposite);
		rebasePlanSection.setClient(rebasePlanTableComposite);
		GridLayoutFactory.fillDefaults().extendedMargins(2, 2, 2, 2)
				.applyTo(rebasePlanTableComposite);

		Composite toolbarComposite = toolkit.createComposite(rebasePlanSection);
		toolbarComposite.setBackground(null);
		RowLayout toolbarRowLayout = new RowLayout();
		toolbarRowLayout.marginHeight = 0;
		toolbarRowLayout.marginWidth = 0;
		toolbarRowLayout.marginTop = 0;
		toolbarRowLayout.marginBottom = 0;
		toolbarRowLayout.marginLeft = 0;
		toolbarRowLayout.marginRight = 0;
		toolbarComposite.setLayout(toolbarRowLayout);

		GridLayoutFactory.fillDefaults().extendedMargins(2, 2, 2, 2)
				.applyTo(rebasePlanTableComposite);

		final Tree planTree = toolkit.createTree(rebasePlanTableComposite,
				SWT.FULL_SELECTION | SWT.MULTI);
		planTree.setHeaderVisible(true);
		planTree.setLinesVisible(false);

		TreeViewer viewer = new TreeViewer(planTree);
		viewer.addSelectionChangedListener(new PlanViewerSelectionChangedListener());
		GridDataFactory.fillDefaults().grab(true, true)
				.applyTo(viewer.getControl());
		viewer.getTree().setData(FormToolkit.KEY_DRAW_BORDER,
				FormToolkit.TREE_BORDER);
		viewer.setContentProvider(RebaseInteractivePlanContentProvider.INSTANCE);
		return viewer;
	}

	private SashForm createRebasePlanSashForm(final Form parent,
			final FormToolkit toolkit) {
		SashForm sashForm = new SashForm(parent.getBody(), SWT.NONE);
		toolkit.adapt(sashForm, true, true);
		GridDataFactory.fillDefaults().grab(true, true).applyTo(sashForm);
		return sashForm;
	}

	private Form createForm(Composite parent, final FormToolkit toolkit) {
		Form newForm = toolkit.createForm(parent);

		Image repoImage = UIIcons.REPOSITORY.createImage();
		UIUtils.hookDisposal(newForm, repoImage);
		newForm.setImage(repoImage);
		newForm.setText(UIText.RebaseInteractiveView_NoSelection);
		GridDataFactory.fillDefaults().grab(true, true).applyTo(newForm);
		toolkit.decorateFormHeading(newForm);
		GridLayoutFactory.swtDefaults().applyTo(newForm.getBody());

		return newForm;
	}

	private void setupListeners() {
		setupRepositoryViewSelectionChangeListener();
		refreshUI();
	}

	private void setupRepositoryViewSelectionChangeListener() {
		selectionChangedListener = new ISelectionListener() {

			@Override
			public void selectionChanged(IWorkbenchPart part,
					ISelection selection) {
				if (!listenOnRepositoryViewSelection
						|| part == getSite().getPart())
					return;

				// this may happen if we switch between editors
				if (part instanceof IEditorPart) {
					IEditorInput input = ((IEditorPart) part).getEditorInput();
					if (input instanceof IFileEditorInput)
						setInput(new StructuredSelection(
								((IFileEditorInput) input).getFile()));
				} else
					setInput(selection);
			}
		};

		ISelectionService srv = CommonUtils.getService(getSite(), ISelectionService.class);
		srv.addPostSelectionListener(selectionChangedListener);
	}

	private void reactOnInitalSelection() {
		selectionChangedListener.selectionChanged(initialSelection.activeEditor,
				initialSelection.selection);
		this.initialSelection = null;
	}

	private static final class InitialSelection {
		ISelection selection;

		IEditorPart activeEditor;

		InitialSelection(ISelection selection) {
			this.selection = selection;
		}
	}

	private class RebaseCommandItemSelectionListener extends SelectionAdapter {

		private final AbstractRebaseCommandHandler command;

		public RebaseCommandItemSelectionListener(
				AbstractRebaseCommandHandler command) {
			super();
			this.command = command;
		}

		@Override
		public void widgetSelected(SelectionEvent sEvent) {
			try {
				Repository repository = currentPlan.getRepository();
				if (repository != null) {
					command.execute(repository);
				}
			} catch (ExecutionException e) {
				Activator.showError(e.getMessage(), e);
			}
		}
	}

	private class PlanViewerSelectionChangedListener implements
			ISelectionChangedListener {

		@Override
		public void selectionChanged(SelectionChangedEvent event) {
			if (event == null)
				return;
			ISelection selection = event.getSelection();
			actionToolBarProvider.mapActionItemsToSelection(selection);
		}
	}

	private void createLocalDragandDrop() {
		planTreeViewer.addDragSupport(DND.DROP_MOVE | DND.DROP_COPY
				| DND.DROP_LINK,
				new Transfer[] { LocalSelectionTransfer.getTransfer() },
				new RebaseInteractiveDragSourceListener(this));
		planTreeViewer.addDropSupport(DND.DROP_MOVE,
				new Transfer[] { LocalSelectionTransfer.getTransfer() },
				new RebaseInteractiveDropTargetListener(this, planTreeViewer));
	}

	private void createStepActionToolBar(Section rebasePlanSection,
			final FormToolkit toolkit) {
		actionToolBarProvider = new RebaseInteractiveStepActionToolBarProvider(
				rebasePlanSection, SWT.FLAT | SWT.WRAP, this);
		toolkit.adapt(actionToolBarProvider.getTheToolbar());
		toolkit.paintBordersFor(actionToolBarProvider.getTheToolbar());
		rebasePlanSection.setTextClient(actionToolBarProvider.getTheToolbar());
	}

	private static RebaseInteractivePlan.ElementType getType(Object element) {
		if (element instanceof PlanElement) {
			PlanElement planLine = (PlanElement) element;
			return planLine.getElementType();
		} else
			return null;
	}

	private static class HighlightingColumnLabelProvider extends
			ColumnLabelProvider {

		@Override
		public Font getFont(Object element) {
			ElementType t = RebaseInteractiveView.getType(element);
			if (t != null && t == ElementType.DONE_CURRENT)
				return UIUtils.getBoldFont(JFaceResources.DIALOG_FONT);
			return super.getFont(element);
		}
	}

	private void createColumns(TreeColumnLayout layout) {
		String[] headings = { UIText.RebaseInteractiveView_HeadingStatus,
				UIText.RebaseInteractiveView_HeadingStep,
				UIText.RebaseInteractiveView_HeadingAction,
				UIText.RebaseInteractiveView_HeadingCommitId,
				UIText.RebaseInteractiveView_HeadingMessage,
				UIText.RebaseInteractiveView_HeadingAuthor,
				UIText.RebaseInteractiveView_HeadingAuthorDate,
				UIText.RebaseInteractiveView_HeadingCommitter,
				UIText.RebaseInteractiveView_HeadingCommitDate };

		ColumnViewerToolTipSupport.enableFor(planTreeViewer,
				ToolTip.NO_RECREATE);

		TreeViewerColumn infoColumn = createColumn(headings[0]);
		layout.setColumnData(infoColumn.getColumn(),
				new ColumnPixelData(70));
		infoColumn.setLabelProvider(new HighlightingColumnLabelProvider() {

			@Override
			public Image getImage(Object element) {
				ElementType t = getType(element);
				if (t != null) {
					switch (t) {
					case DONE_CURRENT:
						return UIIcons
								.getImage(resources, UIIcons.CURRENT_STEP);
					case DONE:
						return UIIcons.getImage(resources, UIIcons.DONE_STEP);
					default:
						// fall through
					}
				}
				return null;
			}

			@Override
			public String getToolTipText(Object element) {
				ElementType t = getType(element);
				if (t != null) {
					switch (t) {
					case DONE:
						return UIText.RebaseInteractiveView_StatusDone;
					case DONE_CURRENT:
						return UIText.RebaseInteractiveView_StatusCurrent;
					case TODO:
						return UIText.RebaseInteractiveView_StatusTodo;
					default:
						// fall through
					}
				}
				return ""; //$NON-NLS-1$
			}

			@Override
			public String getText(Object element) {
				return ""; //$NON-NLS-1$
			}
		});

		TreeViewerColumn stepColumn = createColumn(headings[1]);
		layout.setColumnData(stepColumn.getColumn(),
				new ColumnPixelData(55));
		stepColumn.setLabelProvider(new HighlightingColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PlanElement) {
					PlanElement planLine = (PlanElement) element;
					return (planIndexer.indexOf(planLine) + 1) + "."; //$NON-NLS-1$
				}
				return super.getText(element);
			}
		});
		stepColumn.getColumn().addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				Tree tree = planTreeViewer.getTree();

				boolean orderReversed = tree.getSortDirection() == SWT.DOWN;

				RebaseInteractivePreferences.setOrderReversed(!orderReversed);

				int newDirection = (orderReversed ? SWT.UP : SWT.DOWN);
				tree.setSortDirection(newDirection);

				TreeItem topmostVisibleItem = tree.getTopItem();
				refreshUI();
				if (topmostVisibleItem != null)
					tree.showItem(topmostVisibleItem);
			}
		});

		int direction = (RebaseInteractivePreferences.isOrderReversed() ? SWT.DOWN
				: SWT.UP);

		Tree planTree = planTreeViewer.getTree();
		planTree.setSortColumn(stepColumn.getColumn());
		planTree.setSortDirection(direction);

		TreeViewerColumn actionColumn = createColumn(headings[2]);
		layout.setColumnData(actionColumn.getColumn(),
				new ColumnPixelData(90));
		actionColumn.setLabelProvider(new HighlightingColumnLabelProvider() {

			@Override
			public Image getImage(Object element) {
				ElementAction a = getAction(element);
				if (a != null) {
					switch (a) {
					case EDIT:
						return UIIcons.getImage(resources, UIIcons.EDITCONFIG);
					case FIXUP:
						if (RebaseInteractivePreferences.isOrderReversed())
							return UIIcons.getImage(resources,
									UIIcons.FIXUP_DOWN);
						else
							return UIIcons.getImage(resources,
									UIIcons.FIXUP_UP);
					case PICK:
						return UIIcons.getImage(resources, UIIcons.CHERRY_PICK);
					case REWORD:
						return UIIcons.getImage(resources, UIIcons.REWORD);
					case SKIP:
						return UIIcons.getImage(resources, UIIcons.REBASE_SKIP);
					case SQUASH:
						if (RebaseInteractivePreferences.isOrderReversed())
							return UIIcons.getImage(resources,
									UIIcons.SQUASH_DOWN);
						else
							return UIIcons.getImage(resources,
									UIIcons.SQUASH_UP);
					default:
						// fall through
					}
				}
				return super.getImage(element);
			}

			@Override
			public String getText(Object element) {
				ElementAction a = getAction(element);
				return (a != null) ? a.name() : super.getText(element);
			}

			private ElementAction getAction(Object element) {
				if (element instanceof PlanElement) {
					PlanElement planLine = (PlanElement) element;
					return planLine.getPlanElementAction();
				} else
					return null;
			}
		});

		TreeViewerColumn commitIDColumn = createColumn(headings[3]);
		layout.setColumnData(commitIDColumn.getColumn(),
				new ColumnPixelData(70));
		commitIDColumn.setLabelProvider(new HighlightingColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PlanElement) {
					PlanElement planLine = (PlanElement) element;
					return planLine.getCommit().name();
				}
				return super.getText(element);
			}
		});

		TreeViewerColumn commitMessageColumn = createColumn(headings[4]);
		layout.setColumnData(commitMessageColumn.getColumn(),
				new ColumnWeightData(200, 200));
		commitMessageColumn
				.setLabelProvider(new HighlightingColumnLabelProvider() {
					@Override
					public String getText(Object element) {
						if (element instanceof PlanElement) {
							PlanElement planLine = (PlanElement) element;
							return planLine.getShortMessage();
						}
						return super.getText(element);
					}
				});

		TreeViewerColumn authorColumn = createColumn(headings[5]);
		layout.setColumnData(authorColumn.getColumn(),
				new ColumnWeightData(120, 120));
		authorColumn.setLabelProvider(new HighlightingColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PlanElement) {
					PlanElement planLine = (PlanElement) element;
					return planLine.getAuthor();
				}
				return super.getText(element);
			}
		});

		TreeViewerColumn authoredDateColumn = createColumn(headings[6]);
		layout.setColumnData(authoredDateColumn.getColumn(),
				new ColumnWeightData(80, 80));
		authoredDateColumn
				.setLabelProvider(new HighlightingColumnLabelProvider() {
					@Override
					public String getText(Object element) {
						if (element instanceof PlanElement) {
							PlanElement planLine = (PlanElement) element;
							return planLine.getAuthoredDate(dateFormatter);
						}
						return super.getText(element);
					}
				});

		TreeViewerColumn committerColumn = createColumn(headings[7]);
		layout.setColumnData(committerColumn.getColumn(),
				new ColumnWeightData(120, 120));
		committerColumn.setLabelProvider(new HighlightingColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PlanElement) {
					PlanElement planLine = (PlanElement) element;
					return planLine.getCommitter();
				}
				return super.getText(element);
			}
		});

		TreeViewerColumn commitDateColumn = createColumn(headings[8]);
		layout.setColumnData(commitDateColumn.getColumn(),
				new ColumnWeightData(80, 80));
		commitDateColumn
				.setLabelProvider(new HighlightingColumnLabelProvider() {
					@Override
					public String getText(Object element) {
						if (element instanceof PlanElement) {
							PlanElement planLine = (PlanElement) element;
							return planLine.getCommittedDate(dateFormatter);
						}
						return super.getText(element);
					}
				});
		dynamicColumns = new TreeViewerColumn[] { commitMessageColumn,
				authorColumn, authoredDateColumn, committerColumn,
				commitDateColumn };
	}

	private TreeViewerColumn createColumn(String text) {
		TreeViewerColumn column = new TreeViewerColumn(planTreeViewer, SWT.NONE);
		column.getColumn().setText(text);
		column.getColumn().setMoveable(false);
		return column;
	}

	private void asyncExec(Runnable runnable) {
		PlatformUI.getWorkbench().getDisplay().asyncExec(runnable);
	}

	private static String getRepositoryName(Repository repository) {
		String repoName = Activator.getDefault().getRepositoryUtil()
				.getRepositoryName(repository);
		RepositoryState state = repository.getRepositoryState();
		if (state != RepositoryState.SAFE)
			return repoName + '|' + state.getDescription();
		else
			return repoName;
	}

	private void showRepository(final Repository repository) {
		if (form.isDisposed())
			return;

		if (currentPlan != null)
			currentPlan.removeRebaseInteractivePlanChangeListener(this);

		if (planIndexer != null)
			planIndexer.dispose();

		if (isValidRepo(repository)) {
			currentPlan = RebaseInteractivePlan.getPlan(repository);
			planIndexer = new RebasePlanIndexer(currentPlan);
			currentPlan.addRebaseInteractivePlanChangeListener(this);
			form.setText(getRepositoryName(repository));
		} else {
			currentPlan = null;
			planIndexer = null;
			form.setText(UIText.RebaseInteractiveView_NoSelection);
		}
		refresh();
	}

	private boolean isValidRepo(final Repository repository) {
		return repository != null && !repository.isBare()
				&& repository.getWorkTree().exists();
	}

	void refresh() {
		if (!isReady())
			return;
		asyncExec(new Runnable() {
			@Override
			public void run() {
				Tree t = planTreeViewer.getTree();
				if (t.isDisposed())
					return;
				t.setRedraw(false);
				try {
					planTreeViewer.setInput(currentPlan);
					refreshUI();
				} finally {
					t.setRedraw(true);
				}
			}
		});

	}

	private boolean isReady() {
		IWorkbenchPartSite site = this.getSite();
		if (site == null)
			return false;
		return !site.getShell().isDisposed();
	}

	private void refreshUI() {
		dateFormatter = getNewDateFormatter();
		if (planTreeViewer != null) {
			planTreeViewer.refresh(true);
			// make column widths match the contents
			for (TreeViewerColumn col : dynamicColumns) {
				col.getColumn().pack();
			}
			// Re-distribute the space again, now that we know the true minimum
			// widths.
			for (TreeViewerColumn col : dynamicColumns) {
				int width = col.getColumn().getWidth();
				// Use width as weight, too.
				planLayout.setColumnData(col.getColumn(),
						new ColumnWeightData(width, width));
			}
			planLayout.layout(planTreeViewer.getTree().getParent(), true);
		}

		startItem.setEnabled(false);
		continueItem.setEnabled(false);
		skipItem.setEnabled(false);
		abortItem.setEnabled(false);
		dndEnabled = false;

		actionToolBarProvider.getTheToolbar().setEnabled(false);

		if (currentPlan == null || !currentPlan.isRebasingInteractive()) {
			if (currentRepository == null)
				form.setText(UIText.RebaseInteractiveView_NoSelection);
			else
				form.setText(getRepositoryName(currentRepository));
			return;
		}

		actionToolBarProvider.mapActionItemsToSelection(planTreeViewer
				.getSelection());
		if (!currentPlan.hasRebaseBeenStartedYet()) {
			if (!planTreeViewer.getSelection().isEmpty())
				actionToolBarProvider.getTheToolbar().setEnabled(true);

			startItem.setEnabled(true);
			abortItem.setEnabled(true);
			dndEnabled = true;
		} else {
			continueItem.setEnabled(true);
			skipItem.setEnabled(true);
			abortItem.setEnabled(true);
		}

		if (RebaseInteractivePreferences.isOrderReversed()) {
			Tree tree = planTreeViewer.getTree();
			int itemCount = tree.getItemCount();
			if (itemCount > 0) {
				TreeItem bottomItem = tree.getItem(itemCount - 1);
				tree.showItem(bottomItem);
			}
		}
	}

	private void createPopupMenu(final TreeViewer planViewer) {
		createContextMenuItems(planViewer);

		MenuManager manager = new MenuManager();
		manager.addMenuListener(new IMenuListener() {
			@Override
			public void menuAboutToShow(IMenuManager menuManager) {
				boolean selectionNotEmpty = !planViewer.getSelection()
						.isEmpty();
				boolean rebaseNotStarted = !currentPlan
						.hasRebaseBeenStartedYet();
				boolean menuEnabled = selectionNotEmpty && rebaseNotStarted;
				for (PlanContextMenuAction item : contextMenuItems)
					item.setEnabled(menuEnabled);
			}
		});

		for (PlanContextMenuAction item : contextMenuItems)
			manager.add(item);

		Menu menu = manager.createContextMenu(planViewer.getControl());
		planViewer.getControl().setMenu(menu);
	}

	private void createContextMenuItems(final TreeViewer planViewer) {
		contextMenuItems = new ArrayList<>();

		contextMenuItems.add(new PlanContextMenuAction(
				UIText.RebaseInteractiveStepActionToolBarProvider_PickText,
				UIIcons.CHERRY_PICK, RebaseInteractivePlan.ElementAction.PICK,
				planViewer, actionToolBarProvider));
		contextMenuItems.add(new PlanContextMenuAction(
				UIText.RebaseInteractiveStepActionToolBarProvider_SkipText,
				UIIcons.REBASE_SKIP, RebaseInteractivePlan.ElementAction.SKIP,
				planViewer, actionToolBarProvider));
		contextMenuItems.add(new PlanContextMenuAction(
				UIText.RebaseInteractiveStepActionToolBarProvider_EditText,
				UIIcons.EDITCONFIG, RebaseInteractivePlan.ElementAction.EDIT,
				planViewer, actionToolBarProvider));
		contextMenuItems.add(new PlanContextMenuAction(
				UIText.RebaseInteractiveStepActionToolBarProvider_SquashText,
				UIIcons.SQUASH_UP, RebaseInteractivePlan.ElementAction.SQUASH,
				planViewer, actionToolBarProvider));
		contextMenuItems.add(new PlanContextMenuAction(
				UIText.RebaseInteractiveStepActionToolBarProvider_FixupText,
				UIIcons.FIXUP_UP, RebaseInteractivePlan.ElementAction.FIXUP,
				planViewer, actionToolBarProvider));
		contextMenuItems.add(new PlanContextMenuAction(
				UIText.RebaseInteractiveStepActionToolBarProvider_RewordText,
				UIIcons.REWORD, RebaseInteractivePlan.ElementAction.REWORD,
				planViewer, actionToolBarProvider));
	}

	private static GitDateFormatter getNewDateFormatter() {
		boolean useRelativeDates = Activator.getDefault().getPreferenceStore()
				.getBoolean(UIPreferences.RESOURCEHISTORY_SHOW_RELATIVE_DATE);
		if (useRelativeDates)
			return new GitDateFormatter(Format.RELATIVE);
		else
			return PreferenceBasedDateFormatter.create();
	}

	@Override
	public void setFocus() {
		planTreeViewer.getControl().setFocus();
	}

	boolean isDragAndDropEnabled() {
		return dndEnabled;
	}

	@Override
	public void planWasUpdatedFromRepository(final RebaseInteractivePlan plan) {
		refresh();
	}

	@Override
	public void planElementTypeChanged(
			RebaseInteractivePlan rebaseInteractivePlan, PlanElement element,
			ElementAction oldType, ElementAction newType) {
		planTreeViewer.refresh(element, true);
	}

	@Override
	public void planElementsOrderChanged(
			RebaseInteractivePlan rebaseInteractivePlan, PlanElement element,
			int oldIndex, int newIndex) {
		planTreeViewer.refresh(true);
	}

	private static class PlanLayout extends TreeColumnLayout {
		@Override
		protected void layout(Composite composite, boolean flushCache) {
			// Just to get access to this in the enclosing class.
			super.layout(composite, flushCache);
		}
	}
}
