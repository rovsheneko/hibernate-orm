// $Id:$
/*
* JBoss, Home of Professional Open Source
* Copyright 2009, Red Hat, Inc. and/or its affiliates, and individual contributors
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
* http://www.apache.org/licenses/LICENSE-2.0
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.hibernate.jpamodelgen.xml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.persistence.AccessType;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.xml.sax.SAXException;

import org.hibernate.jpamodelgen.AccessTypeInformation;
import org.hibernate.jpamodelgen.Context;
import org.hibernate.jpamodelgen.util.Constants;
import org.hibernate.jpamodelgen.util.StringUtil;
import org.hibernate.jpamodelgen.util.TypeUtils;
import org.hibernate.jpamodelgen.xml.jaxb.Entity;
import org.hibernate.jpamodelgen.xml.jaxb.EntityMappings;
import org.hibernate.jpamodelgen.xml.jaxb.ObjectFactory;
import org.hibernate.jpamodelgen.xml.jaxb.Persistence;
import org.hibernate.jpamodelgen.xml.jaxb.PersistenceUnitDefaults;
import org.hibernate.jpamodelgen.xml.jaxb.PersistenceUnitMetadata;

/**
 * @author Hardy Ferentschik
 */
public class XmlParser {
	private static final String ORM_XML = "/META-INF/orm.xml";
	private static final String PERSISTENCE_XML_XSD = "persistence_2_0.xsd";
	private static final String ORM_XSD = "orm_2_0.xsd";

	private Context context;
	private List<EntityMappings> entityMappings;

	public XmlParser(Context context) {
		this.context = context;
		this.entityMappings = new ArrayList<EntityMappings>();
	}

	public void parseXml() {
		collectAllEntityMappings();
		determineDefaultAccessTypeAndMetaCompleteness();
		determineXmlAccessTypes();
		if ( !context.isPersistenceUnitCompletelyXmlConfigured() ) {
			// need to take annotations into consideration, since they can override xml settings
			// we have to at least determine whether any of the xml configured entities is influenced by annotations
			determineAnnotationAccessTypes();
		}

		for ( EntityMappings mappings : entityMappings ) {
			String defaultPackageName = mappings.getPackage();
			parseEntities( mappings.getEntity(), defaultPackageName );
			parseEmbeddable( mappings.getEmbeddable(), defaultPackageName );
			parseMappedSuperClass( mappings.getMappedSuperclass(), defaultPackageName );
		}
	}

	private void collectAllEntityMappings() {
		Persistence persistence = parseXml(
				context.getPersistenceXmlLocation(), Persistence.class, PERSISTENCE_XML_XSD
		);
		if ( persistence != null ) {
			List<Persistence.PersistenceUnit> persistenceUnits = persistence.getPersistenceUnit();
			for ( Persistence.PersistenceUnit unit : persistenceUnits ) {
				List<String> mappingFiles = unit.getMappingFile();
				for ( String mappingFile : mappingFiles ) {
					loadEntityMappings( mappingFile );
				}
			}
		}

		// /META-INF/orm.xml is implicit
		loadEntityMappings( ORM_XML );

		// not really part of the official spec, but the processor allows to specify mapping files directly as
		// command line options
		for ( String optionalOrmFiles : context.getOrmXmlFiles() ) {
			loadEntityMappings( optionalOrmFiles );
		}
	}

	private void loadEntityMappings(String resource) {
		EntityMappings mapping = parseXml( resource, EntityMappings.class, ORM_XSD );
		if ( mapping != null ) {
			entityMappings.add( mapping );
		}
	}

	private void parseEntities(Collection<Entity> entities, String defaultPackageName) {
		for ( Entity entity : entities ) {
			String fqcn = StringUtil.determineFullyQualifiedClassName( defaultPackageName, entity.getClazz() );

			if ( !xmlMappedTypeExists( fqcn ) ) {
				context.logMessage(
						Diagnostic.Kind.WARNING,
						fqcn + " is mapped in xml, but class does not exists. Skipping meta model generation."
				);
				continue;
			}

			XmlMetaEntity metaEntity = new XmlMetaEntity(
					entity, defaultPackageName, getXmlMappedType( fqcn ), context
			);
			if ( context.containsMetaEntity( fqcn ) ) {
				context.logMessage(
						Diagnostic.Kind.WARNING,
						fqcn + " was already processed once. Skipping second occurance."
				);
			}
			context.addMetaEntity( fqcn, metaEntity );
		}
	}

	private void parseEmbeddable(Collection<org.hibernate.jpamodelgen.xml.jaxb.Embeddable> embeddables, String defaultPackageName) {
		for ( org.hibernate.jpamodelgen.xml.jaxb.Embeddable embeddable : embeddables ) {
			String fqcn = StringUtil.determineFullyQualifiedClassName( defaultPackageName, embeddable.getClazz() );
			// we have to extract the package name from the fqcn. Maybe the entity was setting a fqcn directly
			String pkg = StringUtil.packageNameFromFqcn( fqcn );

			if ( !xmlMappedTypeExists( fqcn ) ) {
				context.logMessage(
						Diagnostic.Kind.WARNING,
						fqcn + " is mapped in xml, but class does not exists. Skipping meta model generation."
				);
				continue;
			}

			XmlMetaEntity metaEntity = new XmlMetaEmbeddable( embeddable, pkg, getXmlMappedType( fqcn ), context );
			if ( context.containsMetaEmbeddable( fqcn ) ) {
				context.logMessage(
						Diagnostic.Kind.WARNING,
						fqcn + " was already processed once. Skipping second occurance."
				);
			}
			context.addMetaEmbeddable( fqcn, metaEntity );
		}
	}

	private void parseMappedSuperClass(Collection<org.hibernate.jpamodelgen.xml.jaxb.MappedSuperclass> mappedSuperClasses, String defaultPackageName) {
		for ( org.hibernate.jpamodelgen.xml.jaxb.MappedSuperclass mappedSuperClass : mappedSuperClasses ) {
			String fqcn = StringUtil.determineFullyQualifiedClassName(
					defaultPackageName, mappedSuperClass.getClazz()
			);
			// we have to extract the package name from the fqcn. Maybe the entity was setting a fqcn directly
			String pkg = StringUtil.packageNameFromFqcn( fqcn );

			if ( !xmlMappedTypeExists( fqcn ) ) {
				context.logMessage(
						Diagnostic.Kind.WARNING,
						fqcn + " is mapped in xml, but class does not exists. Skipping meta model generation."
				);
				continue;
			}

			XmlMetaEntity metaEntity = new XmlMetaEntity(
					mappedSuperClass, pkg, getXmlMappedType( fqcn ), context
			);

			if ( context.containsMetaEmbeddable( fqcn ) ) {
				context.logMessage(
						Diagnostic.Kind.WARNING,
						fqcn + " was already processed once. Skipping second occurance."
				);
			}
			context.addMetaEntity( fqcn, metaEntity );
		}
	}

	/**
	 * Tries to open the specified xml file and return an instance of the specified class using JAXB.
	 *
	 * @param resource the xml file name
	 * @param clazz The type of jaxb node to return
	 * @param schemaName The schema to validate against (can be {@code null});
	 *
	 * @return The top level jaxb instance contained in the xml file or {@code null} in case the file could not be found
	 *         or could not be unmarshalled.
	 */
	private <T> T parseXml(String resource, Class<T> clazz, String schemaName) {

		InputStream stream = getInputStreamForResource( resource );

		if ( stream == null ) {
			context.logMessage( Diagnostic.Kind.OTHER, resource + " not found." );
			return null;
		}
		try {
			JAXBContext jc = JAXBContext.newInstance( ObjectFactory.class );
			Unmarshaller unmarshaller = jc.createUnmarshaller();
			if ( schemaName != null ) {
				unmarshaller.setSchema( getSchema( schemaName ) );
			}
			return clazz.cast( unmarshaller.unmarshal( stream ) );
		}
		catch ( JAXBException e ) {
			String message = "Error unmarshalling " + resource + " with exception :\n " + e;
			context.logMessage( Diagnostic.Kind.WARNING, message );
			return null;
		}
		catch ( Exception e ) {
			String message = "Error reading " + resource + " with exception :\n " + e;
			context.logMessage( Diagnostic.Kind.WARNING, message );
			return null;
		}
	}

	private Schema getSchema(String schemaName) {
		Schema schema = null;
		URL schemaUrl = this.getClass().getClassLoader().getResource( schemaName );
		if ( schemaUrl == null ) {
			return schema;
		}

		SchemaFactory sf = SchemaFactory.newInstance( javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI );
		try {
			schema = sf.newSchema( schemaUrl );
		}
		catch ( SAXException e ) {
			context.logMessage(
					Diagnostic.Kind.WARNING, "Unable to create schema for " + schemaName + ": " + e.getMessage()
			);
		}
		return schema;
	}

	private InputStream getInputStreamForResource(String resource) {
		String pkg = getPackage( resource );
		String name = getRelativeName( resource );
		context.logMessage( Diagnostic.Kind.OTHER, "Reading resource " + resource );
		InputStream ormStream;
		try {
			FileObject fileObject = context.getProcessingEnvironment()
					.getFiler()
					.getResource( StandardLocation.CLASS_OUTPUT, pkg, name );
			ormStream = fileObject.openInputStream();
		}
		catch ( IOException e1 ) {
			// TODO - METAGEN-12
			// unfortunately, the Filer.getResource API seems not to be able to load from /META-INF. One gets a
			// FilerException with the message with "Illegal name /META-INF". This means that we have to revert to
			// using the classpath. This might mean that we find a persistence.xml which is 'part of another jar.
			// Not sure what else we can do here
			ormStream = this.getClass().getResourceAsStream( resource );
		}
		return ormStream;
	}

	private String getPackage(String resourceName) {
		if ( !resourceName.contains( Constants.PATH_SEPARATOR ) ) {
			return "";
		}
		else {
			return resourceName.substring( 0, resourceName.lastIndexOf( Constants.PATH_SEPARATOR ) );
		}
	}

	private String getRelativeName(String resourceName) {
		if ( !resourceName.contains( Constants.PATH_SEPARATOR ) ) {
			return resourceName;
		}
		else {
			return resourceName.substring( resourceName.lastIndexOf( Constants.PATH_SEPARATOR ) + 1 );
		}
	}

	private boolean xmlMappedTypeExists(String fullyQualifiedClassName) {
		Elements utils = context.getElementUtils();
		return utils.getTypeElement( fullyQualifiedClassName ) != null;
	}

	private TypeElement getXmlMappedType(String fullyQualifiedClassName) {
		Elements utils = context.getElementUtils();
		return utils.getTypeElement( fullyQualifiedClassName );
	}

	private AccessType determineEntityAccessType(EntityMappings mappings) {
		AccessType accessType = context.getPersistenceUnitDefaultAccessType();
		if ( mappings.getAccess() != null ) {
			accessType = mapXmlAccessTypeToJpaAccessType( mappings.getAccess() );
		}
		return accessType;
	}

	private void determineXmlAccessTypes() {
		for ( EntityMappings mappings : entityMappings ) {
			String fqcn;
			String packageName = mappings.getPackage();
			AccessType defaultAccessType = determineEntityAccessType( mappings );

			for ( Entity entity : mappings.getEntity() ) {
				String name = entity.getClazz();
				fqcn = StringUtil.determineFullyQualifiedClassName( packageName, name );
				AccessType explicitAccessType = null;
				org.hibernate.jpamodelgen.xml.jaxb.AccessType type = entity.getAccess();
				if ( type != null ) {
					explicitAccessType = mapXmlAccessTypeToJpaAccessType( type );
				}
				AccessTypeInformation accessInfo = new AccessTypeInformation(
						fqcn, explicitAccessType, defaultAccessType
				);
				context.addAccessTypeInformation( fqcn, accessInfo );
			}

			for ( org.hibernate.jpamodelgen.xml.jaxb.MappedSuperclass mappedSuperClass : mappings.getMappedSuperclass() ) {
				String name = mappedSuperClass.getClazz();
				fqcn = StringUtil.determineFullyQualifiedClassName( packageName, name );
				AccessType explicitAccessType = null;
				org.hibernate.jpamodelgen.xml.jaxb.AccessType type = mappedSuperClass.getAccess();
				if ( type != null ) {
					explicitAccessType = mapXmlAccessTypeToJpaAccessType( type );
				}
				AccessTypeInformation accessInfo = new AccessTypeInformation(
						fqcn, explicitAccessType, defaultAccessType
				);
				context.addAccessTypeInformation( fqcn, accessInfo );
			}

			for ( org.hibernate.jpamodelgen.xml.jaxb.Embeddable embeddable : mappings.getEmbeddable() ) {
				String name = embeddable.getClazz();
				fqcn = StringUtil.determineFullyQualifiedClassName( packageName, name );
				AccessType explicitAccessType = null;
				org.hibernate.jpamodelgen.xml.jaxb.AccessType type = embeddable.getAccess();
				if ( type != null ) {
					explicitAccessType = mapXmlAccessTypeToJpaAccessType( type );
				}
				AccessTypeInformation accessInfo = new AccessTypeInformation(
						fqcn, explicitAccessType, defaultAccessType
				);
				context.addAccessTypeInformation( fqcn, accessInfo );
			}
		}
	}

	private void determineAnnotationAccessTypes() {
		for ( EntityMappings mappings : entityMappings ) {
			String fqcn;
			String packageName = mappings.getPackage();

			for ( Entity entity : mappings.getEntity() ) {
				String name = entity.getClazz();
				fqcn = StringUtil.determineFullyQualifiedClassName( packageName, name );
				TypeElement element = context.getTypeElementForFullyQualifiedName( fqcn );
				if ( element != null ) {
					TypeUtils.determineAccessTypeForHierarchy( element, context );
				}
			}

			for ( org.hibernate.jpamodelgen.xml.jaxb.MappedSuperclass mappedSuperClass : mappings.getMappedSuperclass() ) {
				String name = mappedSuperClass.getClazz();
				fqcn = StringUtil.determineFullyQualifiedClassName( packageName, name );
				TypeElement element = context.getTypeElementForFullyQualifiedName( fqcn );
				if ( element != null ) {
					TypeUtils.determineAccessTypeForHierarchy( element, context );
				}
			}
		}
	}

	/**
	 * Determines the default access type as specified in the <i>persistence-unit-defaults</i> as well as whether the
	 * xml configuration is complete and annotations should be ignored.
	 * <p/>
	 * Note, the spec says:
	 * <ul>
	 * <li>The persistence-unit-metadata element contains metadata for the entire persistence unit. It is
	 * undefined if this element occurs in multiple mapping files within the same persistence unit.</li>
	 * <li>If the xml-mapping-metadata-complete subelement is specified, the complete set of mapping
	 * metadata for the persistence unit is contained in the XML mapping files for the persistence unit, and any
	 * persistence annotations on the classes are ignored.</li>
	 * <li>When the xml-mapping-metadata-complete element is specified, any metadata-complete attributes specified
	 * within the entity, mapped-superclass, and embeddable elements are ignored.<li>
	 * </ul>
	 */
	private void determineDefaultAccessTypeAndMetaCompleteness() {
		for ( EntityMappings mappings : entityMappings ) {
			PersistenceUnitMetadata meta = mappings.getPersistenceUnitMetadata();
			if ( meta != null ) {
				if ( meta.getXmlMappingMetadataComplete() != null ) {
					context.setPersistenceUnitCompletelyXmlConfigured( true );
				}

				PersistenceUnitDefaults persistenceUnitDefaults = meta.getPersistenceUnitDefaults();
				if ( persistenceUnitDefaults != null ) {
					org.hibernate.jpamodelgen.xml.jaxb.AccessType xmlAccessType = persistenceUnitDefaults.getAccess();
					if ( xmlAccessType != null ) {
						context.setPersistenceUnitDefaultAccessType( mapXmlAccessTypeToJpaAccessType( xmlAccessType ) );
					}
				}
				// for simplicity we stop looking for PersistenceUnitMetadata instances. We assume that all files
				// are consistent in the data specified in PersistenceUnitMetadata. If not the behaviour is not specified
				// anyways. It is up to the JPA provider to handle this when bootstrapping
				break;
			}
		}
	}

	private AccessType mapXmlAccessTypeToJpaAccessType(org.hibernate.jpamodelgen.xml.jaxb.AccessType xmlAccessType) {
		switch ( xmlAccessType ) {
			case FIELD: {
				return AccessType.FIELD;
			}
			case PROPERTY: {
				return AccessType.PROPERTY;
			}
		}
		return null;
	}
}

