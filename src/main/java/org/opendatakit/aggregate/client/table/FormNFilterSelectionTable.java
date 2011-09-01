/*
 * Copyright (C) 2011 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.opendatakit.aggregate.client.table;

import java.util.ArrayList;

import org.opendatakit.aggregate.client.AggregateUI;
import org.opendatakit.aggregate.client.FilterSubTab;
import org.opendatakit.aggregate.client.SecureGWT;
import org.opendatakit.aggregate.client.UIUtils;
import org.opendatakit.aggregate.client.exception.FormNotAvailableException;
import org.opendatakit.aggregate.client.filter.FilterGroup;
import org.opendatakit.aggregate.client.filter.FilterSet;
import org.opendatakit.aggregate.client.form.FormSummary;

import com.google.gwt.event.dom.client.ChangeEvent;
import com.google.gwt.event.dom.client.ChangeHandler;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.FlexTable;
import com.google.gwt.user.client.ui.ListBox;

// TODO: address possible inconsistent states

public class FormNFilterSelectionTable extends FlexTable {
  // ui elements
  private ListBox formsBox;
  private ListBox filtersBox;
  private FilterSubTab filterSubTab;

  // state
  private ArrayList<FormSummary> displayedFormList;
  private FormSummary selectedForm;
  private ArrayList<FilterGroup> displayedFilterList;

  public FormNFilterSelectionTable(FilterSubTab filterSubTab) {
    this.filterSubTab = filterSubTab;

    formsBox = new ListBox();
    formsBox.addChangeHandler(new FormChangeDropDownHandler());
    filtersBox = new ListBox();
    filtersBox.addChangeHandler(new FilterChangeDropDownHandler());

    getElement().setId("form_and_goal_selection");
    setHTML(0, 0, "<h2 id=\"form_name\"> Form </h2>");
    setWidget(0, 1, formsBox);
    setHTML(0, 2, "<h2 id=\"form_name\"> Filter </h2>");
    setWidget(0, 3, filtersBox);
  }

  public ListBox getFiltersBox() {
    return filtersBox;
  }
  
  public void update() {
    // Set up the callback object.
    AsyncCallback<ArrayList<FormSummary>> callback = new AsyncCallback<ArrayList<FormSummary>>() {
      public void onFailure(Throwable caught) {
        AggregateUI.getUI().reportError(caught);
      }

      public void onSuccess(ArrayList<FormSummary> formsFromService) {
        AggregateUI.getUI().clearError();
        
        // setup the display with the latest updates
        // update the class state with the updated form list
        displayedFormList = UIUtils.updateFormDropDown(formsBox, displayedFormList, formsFromService);
        
        // update the class state with the currently displayed form
        selectedForm = UIUtils.getFormFromSelection(formsBox, displayedFormList);
        
        updateFilterList();
      }
    };

    // Make the call to the form service.
    SecureGWT.getFormService().getForms(callback);
  }
  
  private synchronized void updateFilterList() {
    AsyncCallback<FilterSet> callback = new AsyncCallback<FilterSet>() {
      @Override
      public void onFailure(Throwable caught) {
        if(caught instanceof FormNotAvailableException) {
          // the form was not available, restart the update process
          update();          
        } else {
          AggregateUI.getUI().reportError(caught);
        }
      }
      
      @Override
      public void onSuccess(FilterSet filterSet) {
        AggregateUI.getUI().clearError();

        // updates the filter dropdown and sets the class state to the newly created filter list
        displayedFilterList = UIUtils.updateFilterDropDown(filtersBox, selectedForm, displayedFilterList, filterSet);
        
        // once the update filter completes update what is being displayed
        updateSelectedFormNFilter();
      }
    };

    // request the update
    if (selectedForm == null) {
      return;
    }
    if (selectedForm.getId() != null) {
    	SecureGWT.getFilterService().getFilterSet(selectedForm.getId(), callback);
    }

  }

  private void updateSelectedFormNFilter() {
    FormSummary form = UIUtils.getFormFromSelection(formsBox, displayedFormList);
    FilterGroup filterGroup = UIUtils.getFilterFromSelection(filtersBox, displayedFilterList);
  
    // verify a form and filter group exist
    if(form == null || filterGroup == null) {
      return;
    }    
  
    filterSubTab.switchFilterGroup(filterGroup);
  }
  
  /**
   * Handler to process the change in the form drop down
   * 
   */
  private class FormChangeDropDownHandler implements ChangeHandler {
    @Override
    public void onChange(ChangeEvent event) {
      AggregateUI.getUI().getTimer().restartTimer();
      FormSummary form = UIUtils.getFormFromSelection(formsBox, displayedFormList);
      if(form != null) {
        selectedForm = form;
      }

      // update filter list based on new form
      // NOTE: the filter list MUST be updated BEFORE the selected updateSelectedFormNFilter() is called
      updateFilterList();      
    }
  }
  
  /**
   * Handler to process the change in the filter drop down
   * 
   */
  private class FilterChangeDropDownHandler implements ChangeHandler {
    @Override
    public void onChange(ChangeEvent event) {
      AggregateUI.getUI().getTimer().restartTimer();
      updateSelectedFormNFilter();
    }
  }
}