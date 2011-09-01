/**
 * Copyright (C) 2010 University of Washington
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.aggregate.form;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.opendatakit.aggregate.datamodel.FormDataModel;
import org.opendatakit.aggregate.datamodel.FormDataModel.ElementType;
import org.opendatakit.aggregate.datamodel.FormElementModel;
import org.opendatakit.aggregate.datamodel.InstanceData;
import org.opendatakit.aggregate.datamodel.SelectChoice;
import org.opendatakit.aggregate.datamodel.TopLevelDynamicBase;
import org.opendatakit.aggregate.datamodel.TopLevelInstanceData;
import org.opendatakit.common.datamodel.BinaryContent;
import org.opendatakit.common.datamodel.BinaryContentRefBlob;
import org.opendatakit.common.datamodel.DynamicCommonFieldsBase;
import org.opendatakit.common.datamodel.RefBlob;
import org.opendatakit.common.persistence.CommonFieldsBase;
import org.opendatakit.common.persistence.DataField;
import org.opendatakit.common.persistence.Datastore;
import org.opendatakit.common.persistence.EntityKey;
import org.opendatakit.common.persistence.Query;
import org.opendatakit.common.persistence.Query.FilterOperation;
import org.opendatakit.common.persistence.exception.ODKDatastoreException;
import org.opendatakit.common.persistence.exception.ODKEntityNotFoundException;
import org.opendatakit.common.persistence.exception.ODKEntityPersistException;
import org.opendatakit.common.security.User;
import org.opendatakit.common.web.CallingContext;

/**
 * Describes everything about the database representation of a given xform as
 * extracted by javarosa during parsing of the xform and as backed by the 
 * FormDataModel within the persistence layer.  The form definition begins
 * with the SubmissionAssocationTable record that maps a particular 
 * (form id, model version, ui version) to a particular database representation.
 * That table also contains flags for the state of the database representation
 * and whether or not submissions are accepted into that representation.
 * All other flags and metadata associated with the xform will be stored
 * separately in the form information table.
 * 
 * @author mitchellsundt@gmail.com
 * @author wbrunette@gmail.com
 * 
 */
public class FormDefinition {
	
	private static final Log logger = LogFactory.getLog(FormDefinition.class.getName());

	/**
	 * Map from the uriSubmissionDataModel key (uuid) to the FormDefinition.
	 * If forms are deleted and reloaded, they get a different key each time.
	 * The key is defined in the SubmissionAssociationTable.
	 */
	private static final Map<String, FormDefinition> formDefinitions = new HashMap<String, FormDefinition>();
	
	/** the entity that defines the mapping of the form id to this data model */
	private final SubmissionAssociationTable submissionAssociation;
	/** list of all the elements in this submission definition */
	private final List<FormDataModel> elementList = new ArrayList<FormDataModel>();
	/** list of all tables (form, repeat group and auxillary) */
	private final List<FormDataModel> tableList = new ArrayList<FormDataModel>();
	/** list of non-repeat groups in xform */
	private final List<FormDataModel> groupList = new ArrayList<FormDataModel>();
	/** list of structured fields in xform */
	private final List<FormDataModel> geopointList = new ArrayList<FormDataModel>();
	/** map from fully qualified tableName to CFB definition */
	private final Map<String, DynamicCommonFieldsBase> backingTableMap;

	private FormDataModel topLevelGroup = null;
	private FormElementModel topLevelGroupElement = null;
	
	private final String qualifiedTopLevelTable;
	private final XFormParameters xformParameters;
	
	public static final class OrdinalSequence {
		Long ordinal;
		int sequenceCounter;
		
		OrdinalSequence() {
			ordinal = 1L;
			sequenceCounter = 1;
		}
	}
	
	/**
	 * Append to the list the FormDataModel entries needed to represent this
	 * dynamic table.  Useful for generating the FormInfo data model from its 
	 * base tables.
	 * 
	 * @param list
	 * @param definitionKey
	 * @param form
	 * @param topLevel
	 * @param baseUI
	 * @param ordinal
	 * @param datastore
	 * @param user
	 * @return the uri key for the FDM of the table.
	 * @throws ODKDatastoreException
	 */
	static final String buildTableFormDataModel( List<FormDataModel> list, 
													DynamicCommonFieldsBase form, 
													DynamicCommonFieldsBase topLevel, 
													String parentURI,
													OrdinalSequence os,
													CallingContext cc ) throws ODKDatastoreException {
		FormDataModel fdm = FormDataModel.assertRelation(cc);
		FormDataModel d;
		
		if ( topLevel == null || 
			 !( topLevel instanceof TopLevelDynamicBase ) ) {
			throw new IllegalStateException("topLevel entity must be present and a TopLevelDynamicBase!");
		}
		// we are making use of the fact that the PK in the 
		// FormDataModel is the PK within the relation model.
		final EntityKey k = new EntityKey( fdm, topLevel.getUri());
		int idx = parentURI.lastIndexOf('(');
		String parentURI_minusParenPhrase = "elem+" + parentURI;
		if ( idx != -1 ) {
			parentURI_minusParenPhrase = parentURI.substring(0,idx);
		}
		
		String groupUri = form.getUri();
		groupUri = String.format("%1$s(%2$08d)", parentURI_minusParenPhrase, os.sequenceCounter);
		
		// define the table...
		Datastore ds = cc.getDatastore();
		User user = cc.getCurrentUser();
		d = ds.createEntityUsingRelation(fdm, user);
		list.add(d);
		// reset the PK to be the PK of the table we are representing
		d.setStringField(fdm.primaryKey, groupUri);
		d.setOrdinalNumber(os.ordinal);
		d.setParentUriFormDataModel(parentURI);
		d.setUriSubmissionDataModel(topLevel.getUri());
		d.setElementName(form.getTableName());
		d.setElementType((form == topLevel) ?
				FormDataModel.ElementType.GROUP :
				FormDataModel.ElementType.REPEAT );
		d.setPersistAsColumn(null);
		d.setPersistAsTable(form.getTableName());
		d.setPersistAsSchema(form.getSchemaName());
		
		// enforce defined ordinal positions based upon order in fieldList
		
		// loop through the fields...
		os.ordinal = 0L;
		for ( DataField f : form.getFieldList() ) {
			if ( f.getName().startsWith("_")) continue; // ignore metadata
			++(os.ordinal);
			
			// this field should be in the fdm model...
			d = ds.createEntityUsingRelation(fdm, user);
			list.add(d);
			String pkFormatted = String.format("%1$s(%2$08d)", parentURI_minusParenPhrase, ++(os.sequenceCounter));
			d.setStringField(fdm.primaryKey, pkFormatted);
			d.setOrdinalNumber(os.ordinal);
			d.setParentUriFormDataModel(groupUri);
			d.setUriSubmissionDataModel(k.getKey());
			d.setElementName(f.getName());
			switch ( f.getDataType() ) {
			case STRING:
				d.setElementType(FormDataModel.ElementType.STRING);
				break;
			case INTEGER:
				d.setElementType(FormDataModel.ElementType.INTEGER);
				break;
			case DECIMAL:
				d.setElementType(FormDataModel.ElementType.DECIMAL);
				break;
			case BOOLEAN:
				d.setElementType(FormDataModel.ElementType.BOOLEAN);
				break;
			case DATETIME:
				d.setElementType(FormDataModel.ElementType.JRDATETIME);
				break;
			case URI:
			case BINARY: // this data type is hidden under BINARY content structure...
				default:
					throw new IllegalStateException("Unexpected DataType");
			}
			d.setPersistAsColumn(f.getName());
			d.setPersistAsTable(form.getTableName());
			d.setPersistAsSchema(form.getSchemaName());
		}
		
		++(os.ordinal);
		++(os.sequenceCounter);
		return groupUri;
	}
	
	static final void buildBinaryContentFormDataModel( List<FormDataModel> list, 
			String binaryContentElementName,
			String binaryContentTableName,
			String binaryContentRefBlobTableName,
			String refBlobTableName,
			TopLevelDynamicBase topLevel, 
			String parentTableKey,
			OrdinalSequence os,
			CallingContext cc ) throws ODKDatastoreException {
		
		FormDataModel fdm = FormDataModel.assertRelation(cc);
		FormDataModel d;
		
		// we are making use of the fact that the PK in the 
		// FormDataModel is the PK within the relation model.
		final String topLevelURI = topLevel.getUri();
		
		int idx = parentTableKey.lastIndexOf('(');
		String parentURI_minusParenPhrase = "elem+" + parentTableKey;
		if ( idx != -1 ) {
			parentURI_minusParenPhrase = parentTableKey.substring(0,idx);
		}
		String binaryContentUri = String.format("%1$s(%2$08d)", 
				parentURI_minusParenPhrase,
		  		os.sequenceCounter);
		String binaryContentRefBlobUri = String.format("%1$s(%2$08d-%3$s)", 
				parentURI_minusParenPhrase,
		  		os.sequenceCounter, "bc_ref");
		String refBlobUri = String.format("%1$s(%2$08d-%3$s)", 
				parentURI_minusParenPhrase,
		  		os.sequenceCounter, "ref_blob");
		
		Datastore ds = cc.getDatastore();
		User user = cc.getCurrentUser();
		// record for binary content...
		d = ds.createEntityUsingRelation(fdm, user);
		d.setStringField(fdm.primaryKey, binaryContentUri);
		list.add(d);
		final String bcURI = d.getUri();
		d.setOrdinalNumber(os.ordinal);
		d.setParentUriFormDataModel(parentTableKey);
		d.setUriSubmissionDataModel(topLevelURI);
		d.setElementName(binaryContentElementName);
		d.setElementType(FormDataModel.ElementType.BINARY);
		d.setPersistAsColumn(null);
		d.setPersistAsTable(binaryContentTableName);
		d.setPersistAsSchema(fdm.getSchemaName());

		// record for binary content ref blob..
		d = ds.createEntityUsingRelation(fdm, user);
		d.setStringField(fdm.primaryKey, binaryContentRefBlobUri);
		list.add(d);
		final String bcbURI = d.getUri();
		d.setOrdinalNumber(1L);
		d.setParentUriFormDataModel(bcURI);
		d.setUriSubmissionDataModel(topLevelURI);
		d.setElementName(binaryContentElementName);
		d.setElementType(FormDataModel.ElementType.BINARY_CONTENT_REF_BLOB);
		d.setPersistAsColumn(null);
		d.setPersistAsTable(binaryContentRefBlobTableName);
		d.setPersistAsSchema(fdm.getSchemaName());

		// record for ref blob...
		d = ds.createEntityUsingRelation(fdm, user);
		d.setStringField(fdm.primaryKey, refBlobUri);
		list.add(d);
		d.setOrdinalNumber(1L);
		d.setParentUriFormDataModel(bcbURI);
		d.setUriSubmissionDataModel(topLevelURI);
		d.setElementName(binaryContentElementName);
		d.setElementType(FormDataModel.ElementType.REF_BLOB);
		d.setPersistAsColumn(null);
		d.setPersistAsTable(refBlobTableName);
		d.setPersistAsSchema(fdm.getSchemaName());
		
		++(os.ordinal);
		++(os.sequenceCounter);
	}
	
	static final void assertModel(XFormParameters p, List<FormDataModel> model, CallingContext cc) throws ODKDatastoreException {
		FormDataModel fdm = FormDataModel.assertRelation(cc);
		if ( model == null || model.size() == 0 ) {
			throw new IllegalArgumentException("should never be null");
		}
		Datastore ds = cc.getDatastore();
		User user = cc.getCurrentUser();
		for ( FormDataModel m : model ) {
			m.print(System.out);
			try {
				ds.getEntity(fdm, m.getUri(), user);
			} catch ( ODKEntityNotFoundException e ) {
				ds.putEntity(m, user);
			}
		}
	}

	static final FormElementModel findElement(FormElementModel group, DataField backingKey) {
		for ( FormElementModel m : group.getChildren()) {
			if ( m.isMetadata() ) continue;
			if ( m.getFormDataModel().getBackingKey() == backingKey ) return m;
		}
		return null;
	}

	private static final SubmissionAssociationTable getSubmissionAssociation(XFormParameters xformParameters, boolean canBeIncomplete, CallingContext cc ) {
		SubmissionAssociationTable sa = null;
		{
		    List<SubmissionAssociationTable> saList = SubmissionAssociationTable.findSubmissionAssociationsForXForm(xformParameters, cc);
		    if ( saList.isEmpty() ) {
		    	// may be in the process of being defined, or in a partially defined state.
		    	logger.warn("No sa record matching this formId " + xformParameters.toString());
		    	return null;
		    }
		    for ( SubmissionAssociationTable st : saList ) {
		    	if ( canBeIncomplete || st.getIsPersistenceModelComplete() ) {
		    		if ( sa != null ) {
		    			// We have two or more identical entries.  Use the more recent one.
		    			// Presently, can have a duplicate of our main tables because of timing windows.
		    			// Eventually, can have two or more forms with the same submission structure.
				    	logger.warn("Two or more sa records matching this formId " + xformParameters.toString());
		    			if ( sa.getCreationDate().compareTo(st.getCreationDate()) == -1 ) {
		    				// use the more recent data model...
		    				sa = st;
		    			}
				    }
		    		sa = st;
		    	}
		    }
		}
	    return sa;
	}
	/**
	 * 
	 * @param xformParameters  -- the form id, version and ui version of a form definition.
	 * @param uriSubmissionDataModel -- the uri of the definition specification.
	 * @param cc
	 * @return The definition.  The uriSubmissionDataModel is used to ensure that the 
	 * 			currently valid definition of a form is being used (should the form be
	 * 			deleted then reloaded).
	 */
	public static final FormDefinition getFormDefinition(XFormParameters xformParameters, CallingContext cc) {

		if ( xformParameters.formId.indexOf('/') != -1 ) {
			throw new IllegalArgumentException("formId is not well formed: " + xformParameters.formId);
		}

		// always look at SubmissionAssociationTable to retrieve the proper variant
		boolean asDaemon = cc.getAsDeamon();
		try {
			cc.setAsDaemon(true);
			List<? extends CommonFieldsBase> fdmList = null;
			Datastore ds = cc.getDatastore();
			User user = cc.getCurrentUser();
			try {
				SubmissionAssociationTable sa = getSubmissionAssociation( xformParameters, false, cc );
			    if ( sa == null ) {
			    	// must be in a partially defined state.
			    	logger.warn("No complete persistence model for sa record matching this formId " + xformParameters.toString());
			    	return null;
			    }
			    String uriSubmissionDataModel = sa.getUriSubmissionDataModel();

			    // try to retrieve based upon this uri...
			    FormDefinition fd = formDefinitions.get(uriSubmissionDataModel);
			    if ( fd != null ) { 
			    	// found it...
			    	return fd;
			    } else {
			    	// retrieve it...
				    FormDataModel fdm = FormDataModel.assertRelation(cc);
					Query query = ds.createQuery(fdm, user);
					query.addFilter(FormDataModel.URI_SUBMISSION_DATA_MODEL, FilterOperation.EQUAL, uriSubmissionDataModel);
					fdmList = query.executeQuery(0);
					
					if ( fdmList == null || fdmList.size() == 0 ) {
				    	logger.warn("No FDM records for formId " + xformParameters.toString());
						return null;
					}
					
					// try to construct the fd...
					try {
						fd = new FormDefinition(sa, xformParameters, fdmList, cc);
					} catch ( IllegalStateException e) {
						e.printStackTrace();
						logger.error("Form definition is not interpretable for formId " + xformParameters.toString());
						return null;
					}

					// and synchronize field sizes to those defined in the database...
					try {
						// update the form data model with the actual dimensions
						// of its columns -- or create the tables from scratch...
						for ( Map.Entry<String, DynamicCommonFieldsBase> e : fd.backingTableMap.entrySet() ) {
							
							ds.assertRelation(e.getValue(), user);
						}
					} catch (ODKDatastoreException e1) {
						e1.printStackTrace();
				    	logger.error("Asserting relations failed for formId " + xformParameters.toString());
						fd = null;
					}

					// errors might have not cleared the fd...
					if ( fd != null ) {
						// remember details about this form
						formDefinitions.put(uriSubmissionDataModel, fd);
						return fd;
					}
				}
			} catch (ODKDatastoreException e) {
		    	logger.warn("Persistence Layer failure " + e.getMessage() + " for formId " + xformParameters.toString());
				return null;
			}
		} finally {
			cc.setAsDaemon(asDaemon);
		}
		return null;
	}

    static final void forget(String uriSubmissionDataModel) {
		formDefinitions.remove(uriSubmissionDataModel);
	}

	public FormDefinition(SubmissionAssociationTable sa, XFormParameters xformParameters, List<?> formDataModelList, CallingContext cc) {
		this.submissionAssociation = sa;
		this.xformParameters = xformParameters;
		
		// map of tableName to map of columnName, FDM record
		Map<String, Map<String, FormDataModel >> eeMap = new HashMap< String, Map<String, FormDataModel>>();

		Map<String, FormDataModel> uriMap = new HashMap<String, FormDataModel>();
		for ( Object o : formDataModelList ) {
			FormDataModel m = (FormDataModel) o;
			elementList.add(m);
			uriMap.put(m.getUri(), m);
			String table = m.getPersistAsQualifiedTableName();
			String column = m.getPersistAsColumn();
			if ( column != null && table == null ) {
				throw new IllegalStateException("Fdm uri: " + m.getUri() +
					" - Unexpected null persist-as table name when persist-as column name is: "
					+ column );
			}
			if ( column == null ) {
				FormDataModel.ElementType type = m.getElementType();
				if ( table == null ) {
					// should be structured field (e.g., geopoint) or form name.
					switch ( type ) {
					case GEOPOINT:
						geopointList.add(m);
						break;
					default:
						throw new IllegalStateException("Unexpectedly no column and no table for type " + type.toString());
					}
				} else {
					// should be either a structured field (e.g., geopoint),
					// group or repeat element or 
					// one of the auxiliary table types.
					// assume it is for now; will throw an exception later...
					switch ( type ) {
					case GEOPOINT:
						geopointList.add(m);
						break;
					case GROUP:
					case REPEAT:
					case PHANTOM:
						groupList.add(m);
						break;
					default:
						tableList.add(m);
						break;
					}
				}
			} else {
				// a field or structured field part
				Map<String, FormDataModel> mfdm = eeMap.get(table);
				if ( mfdm == null ) {
					mfdm = new HashMap<String, FormDataModel>();
					eeMap.put(table, mfdm);
				}
				mfdm.put(column, m);
			}
		}
		
		// stitch up data model's parent and child links...
		// everything has a parent except the top-level group and 
		// long string text ref tables, which refer to the 
		// key into the form_info table...
		int nullParentCount = 0;
		for ( FormDataModel m : elementList ) {
			String uriParent = m.getParentUriFormDataModel();
			if ( uriParent == null ) {
				String str = "Every record in FormDataModel should have a parent key";
				logger.error(str);
				m.print(System.err);
				throw new IllegalStateException(str);
			}
			
			FormDataModel p = uriMap.get(uriParent);
			if ( p != null ) {
				m.setParent(p);
				p.setChild(m.getOrdinalNumber(), m);
			} else {
				if ( m.getElementType() != ElementType.GROUP ) {
					String str = "Expected upward references only from GROUP elements";
					logger.error(str);
					m.print(System.err);
					throw new IllegalStateException(str);
				}
				if ( ++nullParentCount > 1 ) {
					String str = "Expected at most one top level group";
					logger.error(str);
					m.print(System.err);
					throw new IllegalStateException(str);
				}
				topLevelGroup = m;
			}
		}

		// ensure there are no nulls in the children array.
		// nulls would indicate a skipped ordinal position.
		for ( FormDataModel m : elementList ) {
			m.validateChildren();
		}
		
		// OK.  we have the list of tables, map of fqn's, 
		// form name, non-repeat groups, geopoints, and 
		// fully linked map of parent and children.
		
		// Now construct the descriptions of the tables
		// that represent this form.
		backingTableMap = new HashMap<String, DynamicCommonFieldsBase>();
		for ( FormDataModel m : tableList ) {
			String tableName = (String) m.getPersistAsQualifiedTableName();
			
			DynamicCommonFieldsBase b = backingTableMap.get(tableName);
			if ( b != null ) {
				throw new IllegalStateException("Backing table already linked back: " + tableName);
			}

			switch ( m.getElementType()) {
			case SELECTN:
				b = new SelectChoice(m.getPersistAsSchema(),m.getPersistAsTable());
				m.setBackingObject(b);
				break;
			case BINARY:
				b = new BinaryContent(m.getPersistAsSchema(),m.getPersistAsTable());
				m.setBackingObject(b);
				break;
			case BINARY_CONTENT_REF_BLOB:
				b = new BinaryContentRefBlob(m.getPersistAsSchema(),m.getPersistAsTable());
				m.setBackingObject(b);
				break;
			case REF_BLOB:
				b = new RefBlob(m.getPersistAsSchema(),m.getPersistAsTable());
				m.setBackingObject(b);
				break;
			default:
				throw new IllegalStateException("Unexpectedly no column but has table for type " + m.getElementType().toString());
			}
			backingTableMap.put(tableName, b);
		}
		

		for ( FormDataModel m : groupList ) {
			if ( m.getPersistAsTable() == null ) {
				throw new IllegalStateException("groups, phantoms and repeats should identify their backing table");
			}
			String tableName = m.getPersistAsQualifiedTableName();
			DynamicCommonFieldsBase b = backingTableMap.get(tableName);
			if ( b == null ) {
				/*
				 * Determine if the given group is equivalent to the top level group.  This
				 * occurs when a given group's elements can be collapsed into the top level group
				 * within the persistence layer (the top level group's backing object then holds
				 * the data elements defined within it and within the given group).
				 * When this collapse happens, the group and the parent group share
				 * the same qualified table name.  Phantom and Repeat elements are automatically
				 * not equivalent to the top level group.
				 */
				boolean equivalentToTopLevelGroup = true;
				FormDataModel current = m;
				while ( current != null ) {
					if ( (current.getElementType() == ElementType.REPEAT) ||
						 (current.getElementType() == ElementType.PHANTOM)) {
						// automatically not equivalent
						equivalentToTopLevelGroup = false;
						break;
					}
					FormDataModel parent = current.getParent();
					if ( parent != null && 
						 ( !current.getPersistAsQualifiedTableName().equals(parent.getPersistAsQualifiedTableName())) ) {
						// backing tables are different -- not equivalent!
						equivalentToTopLevelGroup = false;
						break;
					}
					current = parent;
				}

				if ( equivalentToTopLevelGroup ) {
					b = new TopLevelInstanceData(m.getPersistAsSchema(), m.getPersistAsTable());
				} else {
					b = new InstanceData(m.getPersistAsSchema(), m.getPersistAsTable());
				}
				backingTableMap.put(tableName, b);
			}
			m.setBackingObject(b);
		}

		// set the backing object for the geopointList.
		// Geopoint value fields are all stored within the same table...
		// if the backing table was not yet defined by the groupList loop
		// above, then the backing table will never be equivalent to 
		// a top-level group.
		for ( FormDataModel m : geopointList ) {
			if ( m.getPersistAsTable() == null ) {
				throw new IllegalStateException("geopoints should identify their backing table");
			}
			String tableName = m.getPersistAsQualifiedTableName();
			DynamicCommonFieldsBase b = backingTableMap.get(tableName);
			if ( b == null ) {
				b = new InstanceData(m.getPersistAsSchema(), m.getPersistAsTable());
				backingTableMap.put(tableName, b);
			}
			m.setBackingObject(b);
		}

		// and now handle the primitive data elements in the main form...
		// all the backing tables must have been created at this point, 
		// so it is a logic error if we find one that isn't.
		for ( Map.Entry<String, Map<String, FormDataModel>> e : eeMap.entrySet() ) {
			String tableName = e.getKey();
			DynamicCommonFieldsBase b = backingTableMap.get(tableName);
			Collection<FormDataModel> c = e.getValue().values();
			
			// we should have created all the backing tables in the previous
			// two loops.  If not, it is a logic error.
			if ( b == null ) {
				throw new IllegalStateException("Backing table is not yet defined!");
			}

			for ( FormDataModel m : c) {
				DataField.DataType dataType = DataField.DataType.STRING;
				switch ( m.getElementType() ) {
				case STRING:
					dataType = DataField.DataType.STRING;
					break;
				case JRDATETIME:
				case JRDATE:
				case JRTIME:
					dataType = DataField.DataType.DATETIME;
					break;
				case INTEGER:
					dataType = DataField.DataType.INTEGER;
					break;
				case DECIMAL:
					dataType = DataField.DataType.DECIMAL;
					break;
				case BOOLEAN:
					dataType = DataField.DataType.BOOLEAN;
					break;
				default:
					String name = m.getElementName();
					if ( name == null ) name = "--blank--";
					throw new IllegalStateException("Element: " + name + "uri: " + m.getUri() + "Unexpected data type: " + m.getElementType().toString());
				}
				
				DataField dfd = null;
				dfd = new DataField(m.getPersistAsColumn(), dataType, true);
				b.addDataField(dfd);
				m.setBackingKey(dfd);
				m.setBackingObject(b);
			}
		}
		
		if ( topLevelGroup == null ) {
			throw new IllegalStateException("Top level group could not be found");
		}

		if ( topLevelGroup.getElementType() != ElementType.GROUP ) {
			throw new IllegalStateException("Top level group is a non-group!");
		}

		qualifiedTopLevelTable = topLevelGroup.getPersistAsQualifiedTableName();
		
		topLevelGroupElement = FormElementModel.buildFormElementModelTree(topLevelGroup);
	}

	public static void deleteAbnormalModel(XFormParameters xformParameters, CallingContext cc) {
		boolean asDaemon = cc.getAsDeamon();
		try {
			cc.setAsDaemon(true);
			List<? extends CommonFieldsBase> fdmList = null;
			Datastore ds = cc.getDatastore();
			User user = cc.getCurrentUser();
			try {
				SubmissionAssociationTable sa = getSubmissionAssociation( xformParameters, true, cc );
				while ( sa != null ) {
					// prevent the form definition from being used...
					sa.setIsPersistenceModelComplete(false);
					sa.setIsSubmissionAllowed(false);
					ds.putEntity(sa, user);
					// forget us in the local cache...
				    forget(sa.getUriSubmissionDataModel());
				    
				    String uriSubmissionDataModel = sa.getUriSubmissionDataModel();
				    
			    	// retrieve it...
				    FormDataModel fdm = FormDataModel.assertRelation(cc);
					Query query = ds.createQuery(fdm, user);
					query.addFilter(FormDataModel.URI_SUBMISSION_DATA_MODEL, FilterOperation.EQUAL, uriSubmissionDataModel);
					fdmList = query.executeQuery(0);
						
					if ( fdmList == null || fdmList.size() == 0 ) {
						return;
					}
	
					// delete the form data model...
					List<EntityKey> eks = new ArrayList<EntityKey>();
				    for ( CommonFieldsBase m : fdmList ) {
						eks.add(new EntityKey(m, m.getUri()));
				    }
				    ds.deleteEntities(eks, user);
				    
				    // and delete the SA record
				    ds.deleteEntity(new EntityKey(sa, sa.getUri()), user);
				    // just in case...
				    forget(uriSubmissionDataModel);
				    
				    // and see if we have anything more to clean up...
				    sa = getSubmissionAssociation( xformParameters, true, cc );
				}
			    
			    // we don't delete the data tables -- the user may want to manually recover the data
			    
			} catch (ODKDatastoreException e) {
		    	logger.warn("Persistence Layer failure deleting abnormal form definition " + e.getMessage() + " for formId " + xformParameters.toString());
			}
		} finally {
			cc.setAsDaemon(asDaemon);
		}
	}

	public final void deleteDataModel(CallingContext cc) throws ODKDatastoreException {
		User user = cc.getCurrentUser();
		Datastore ds = cc.getDatastore();

		// prevent the form definition from being used...
		submissionAssociation.setIsPersistenceModelComplete(false);
		submissionAssociation.setIsSubmissionAllowed(false);
		ds.putEntity(submissionAssociation, user);
		// forget us in the local cache...
	    forget(submissionAssociation.getUriSubmissionDataModel());

	    FormDataModel fdm = FormDataModel.assertRelation(cc);
		List<EntityKey> eks = new ArrayList<EntityKey>();
		// queue everything in the formDataModel for delete
	    for ( FormDataModel m : elementList ) {
			eks.add(new EntityKey(fdm, m.getUri()));
	    }
	    // delete everything out of FDM
	    ds.deleteEntities(eks, user);

	    // drop the tables...
	    for ( CommonFieldsBase b : getBackingTableSet()) {
	    	try {
	    		ds.dropRelation(b, user);
	    	} catch ( ODKDatastoreException e ) {
	    		e.printStackTrace();
	    	}
	    }
		
		// delete the SA table linking to the model (orphans the model)...
		ds.deleteEntity(new EntityKey(submissionAssociation, submissionAssociation.getUri()), user);
		// forget us in the local cache (optimization...)
	    forget(submissionAssociation.getUriSubmissionDataModel());
	}
	
	public void persistSubmissionAssociation(CallingContext cc) throws ODKEntityPersistException {
		// the only mutable part of the form definition is the 
		// submission association table's flags...
		User user = cc.getCurrentUser();
		Datastore ds = cc.getDatastore();
		ds.putEntity(submissionAssociation, user);
	}
	
	public boolean getIsSubmissionAllowed() {
		return submissionAssociation.getIsSubmissionAllowed();
	}
	
	public void setIsSubmissionAllowed(Boolean value) {
		submissionAssociation.setIsSubmissionAllowed(value);
	}
	
	/**
	 * Get the top-level group for this form.
	 * 
	 * @return
	 */
	public final FormDataModel getTopLevelGroup() {
		return topLevelGroup;
	}
	
	public final FormElementModel getTopLevelGroupElement() {
		return topLevelGroupElement;
	}
	
	public final FormElementModel getElementByName(String name) {
		String[] path = name.split("/");
		FormElementModel m = topLevelGroupElement;
		boolean first = true;
		for ( String p : path ) {
			if ( first ) {
				first = false;
				// first entry can be form id...
				if ( xformParameters.formId.equals(p) ) continue; 
			}

			m = getElementByNameHelper(m, p);
			if ( m == null ) return null;
		}
		return m;		
	}
	
	private final FormElementModel getElementByNameHelper(FormElementModel group, String name) {
		if ( group.getElementName() != null && group.getElementName().equals(name)) {
			return group;
		}
		for ( FormElementModel m : group.getChildren() ) {
			// depth first traversal...
			FormElementModel tmp = getElementByNameHelper( m, name);
			if ( tmp != null ) return tmp;
		}
		return null;
	}
	
	public final String getQualifiedTopLevelTable() {
		return qualifiedTopLevelTable;
	}
	
	public CommonFieldsBase getQualifiedTable(String qualifiedTableName) {
		return backingTableMap.get(qualifiedTableName);
	}
	
	public Collection<? extends CommonFieldsBase> getBackingTableSet() {
		return backingTableMap.values();
	}

	public SubmissionAssociationTable getSubmissionAssociation() {
		return submissionAssociation;
	}
	
	public String getFormId() {
		return xformParameters.formId;
	}
	
	public Long getModelVersion() {
		return xformParameters.modelVersion;
	}
	
	public Long getUiVersion() {
		return xformParameters.uiVersion;
	}

	public String getElementKey(String keyString) {
		// TODO pick apart an "odkId" to return the key within... steal code from 0.9.3
		throw new IllegalStateException("unimplemented");
	}
}