/* 
 * JBoss, Home of Professional Open Source 
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved. 
 * See the copyright.txt in the distribution for a 
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use, 
 * modify, copy, or redistribute it subject to the terms and conditions 
 * of the GNU Lesser General Public License, v. 2.1. 
 * This program is distributed in the hope that it will be useful, but WITHOUT A 
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A 
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details. 
 * You should have received a copy of the GNU Lesser General Public License, 
 * v.2.1 along with this distribution; if not, write to the Free Software 
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, 
 * MA  02110-1301, USA.
 */
package org.switchyard.component.soap.composer;

import java.io.StringReader;
import java.util.Iterator;

import javax.xml.namespace.QName;
import javax.xml.soap.MimeHeader;
import javax.xml.soap.MimeHeaders;
import javax.xml.soap.SOAPHeader;
import javax.xml.soap.SOAPHeaderElement;
import javax.xml.soap.SOAPMessage;

import org.switchyard.Context;
import org.switchyard.Property;
import org.switchyard.Scope;
import org.switchyard.common.io.pull.ElementPuller;
import org.switchyard.common.lang.Strings;
import org.switchyard.common.xml.XMLHelper;
import org.switchyard.component.common.composer.BaseRegexContextMapper;
import org.switchyard.component.common.label.ComponentLabel;
import org.switchyard.component.common.label.EndpointLabel;
import org.switchyard.config.Configuration;
import org.switchyard.config.ConfigurationPuller;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * SOAPContextMapper.
 *
 * @author David Ward &lt;<a href="mailto:dward@jboss.org">dward@jboss.org</a>&gt; (C) 2011 Red Hat Inc.
 * @author Magesh Kumar B <mageshbk@jboss.com> (C) 2012 Red Hat Inc.
 */
public class SOAPContextMapper extends BaseRegexContextMapper<SOAPBindingData> {

    private static final String[] SOAP_HEADER_LABELS = new String[]{ComponentLabel.SOAP.label(), EndpointLabel.SOAP.label()};
    private static final String[] SOAP_MIME_LABELS = new String[]{ComponentLabel.SOAP.label(), EndpointLabel.HTTP.label()};

    private SOAPHeadersType _soapHeadersType = null;

    /**
     * Gets the SOAPHeadersType.
     * @return the SOAPHeadersType
     */
    public SOAPHeadersType getSOAPHeadersType() {
        return _soapHeadersType;
    }

    /**
     * Sets the SOAPHeadersType.
     * @param soapHeadersType the SOAPHeadersType
     * @return this instance (useful for chaining)
     */
    public SOAPContextMapper setSOAPHeadersType(SOAPHeadersType soapHeadersType) {
        _soapHeadersType = soapHeadersType;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void mapFrom(SOAPBindingData source, Context context) throws Exception {
        SOAPMessage soapMessage = source.getSOAPMessage();
        if (soapMessage.getSOAPBody().hasFault() && (source.getSOAPFaultInfo() != null)) {
            context.setProperty(SOAPComposition.SOAP_FAULT_INFO, source.getSOAPFaultInfo(), Scope.IN).addLabels(SOAP_HEADER_LABELS);
        }
        @SuppressWarnings("unchecked")
        Iterator<MimeHeader> mimeHeaders = soapMessage.getMimeHeaders().getAllHeaders();
        while (mimeHeaders.hasNext()) {
            MimeHeader mimeHeader = mimeHeaders.next();
            String name = mimeHeader.getName();
            if (matches(name)) {
                String value = mimeHeader.getValue();
                if (value != null) {
                    context.setProperty(name, value, Scope.IN).addLabels(SOAP_MIME_LABELS);
                }
            }
        }
        @SuppressWarnings("unchecked")
        Iterator<SOAPHeaderElement> soapHeaders = soapMessage.getSOAPHeader().examineAllHeaderElements();
        while (soapHeaders.hasNext()) {
            SOAPHeaderElement soapHeader = soapHeaders.next();
            QName qname = soapHeader.getElementQName();
            if (matches(qname)) {
                final Object value;
                switch (_soapHeadersType != null ? _soapHeadersType : SOAPHeadersType.VALUE) {
                    case CONFIG:
                        value = new ConfigurationPuller().pull(soapHeader);
                        break;
                    case DOM:
                        value = soapHeader;
                        break;
                    case VALUE:
                        value = soapHeader.getValue();
                        break;
                    case XML:
                        value = new ConfigurationPuller().pull(soapHeader).toString();
                        break;
                    default:
                        value = null;
                }
                if (value != null) {
                    String name = qname.toString();
                    context.setProperty(name, value, Scope.IN).addLabels(SOAP_HEADER_LABELS);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void mapTo(Context context, SOAPBindingData target) throws Exception {
        SOAPMessage soapMessage = target.getSOAPMessage();
        MimeHeaders mimeHeaders = soapMessage.getMimeHeaders();
        SOAPHeader soapHeader = soapMessage.getSOAPHeader();
        for (Property property : context.getProperties(Scope.OUT)) {
            Object value = property.getValue();
            if (value != null) {
                String name = property.getName();
                if (property.hasLabel(EndpointLabel.HTTP.label())) {
                    if (matches(name)) {
                        mimeHeaders.addHeader(name, String.valueOf(value));
                    }
                } else {
                    QName qname = XMLHelper.createQName(name);
                    boolean qualifiedForSoapHeader = Strings.trimToNull(qname.getNamespaceURI()) != null;
                    if (qualifiedForSoapHeader && matches(qname)) {
                        if (value instanceof Node) {
                            Node domNode = soapHeader.getOwnerDocument().importNode((Node)value, true);
                            soapHeader.appendChild(domNode);
                        } else if (value instanceof Configuration) {
                            Element configElement = new ElementPuller().pull(new StringReader(value.toString()));
                            Node configNode = soapHeader.getOwnerDocument().importNode(configElement, true);
                            soapHeader.appendChild(configNode);
                        } else {
                            String v = value.toString();
                            if (SOAPHeadersType.XML.equals(_soapHeadersType)) {
                                try {
                                    Element xmlElement = new ElementPuller().pull(new StringReader(v));
                                    Node xmlNode = soapHeader.getOwnerDocument().importNode(xmlElement, true);
                                    soapHeader.appendChild(xmlNode);
                                } catch (Throwable t) {
                                    soapHeader.addChildElement(qname).setValue(v);
                                }
                            } else {
                                soapHeader.addChildElement(qname).setValue(v);
                            }
                        }
                    }
                }
            }
        }
    }

}
