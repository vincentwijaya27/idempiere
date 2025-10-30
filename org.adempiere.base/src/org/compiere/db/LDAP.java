/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved.
 * This program is free software; you can redistribute it and/or modify it
 * under the terms version 2 of the GNU General Public License as published
 * by the Free Software Foundation. This program is distributed in the hope
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
 * See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along 
 * with this program; if not, write to the Free Software Foundation, Inc., 
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.
 * You may reach us at: ComPiere, Inc. - http://www.compiere.org/license.html
 * 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA or info@compiere.org 
 *****************************************************************************/
package org.compiere.db;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.logging.Level;

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;

import org.compiere.model.MSysConfig;
import org.compiere.util.CLogger;


/**
 *	LDAP Management Interface
 *	
 *  @author Jorg Janke
 *  @version $Id: LDAP.java,v 1.2 2006/07/30 00:55:13 jjanke Exp $
 */
public class LDAP
{
	static List<String> ouList = new ArrayList<String>(); // CODE ANDI - 20180531
	static int ouCounter = 0; // CODE ANDI - 20180531
	
	/**
	 * 	Validate User
	 *	@param ldapURL provider url - e.g. ldap://dc.compiere.org
	 *	@param domain domain name = e.g. compiere.org
	 *	@param userName user name - e.g. jjanke
	 *	@param password password 
	 *	@return true if validated with ldap
	 */
	public static boolean validate (String ldapURL, String domain, String userName, String password)
	{
		Hashtable<String,String> env = new Hashtable<String,String>();
		env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
		//	ldap://dc.compiere.org
		env.put(Context.PROVIDER_URL, ldapURL);
		env.put(Context.SECURITY_AUTHENTICATION, "simple");
		
		getOUList(env); // CODE ANDI - 20180531

		StringBuilder principal;
		if ("openldap".equals(MSysConfig.getValue(MSysConfig.LDAP_TYPE))) {
			principal = new StringBuilder("uid=").append(userName).append(",").append(domain);
		} else {
			principal = new StringBuilder(userName).append("@").append(domain);
		}
		env.put(Context.SECURITY_PRINCIPAL, principal.toString());
		env.put(Context.SECURITY_CREDENTIALS, password);
		//
		try
		{
			// Create the initial context
			InitialLdapContext ctx = new InitialLdapContext(env, null);
			
			//	Test - Get the attributes
			ctx.getAttributes("");

		    // Print the answer
		    //if (false)
		    // dump (answer);
		}
		catch (AuthenticationException e)
		{
			// BEGIN CODE ANDI - 20180531
			if(ouCounter < ouList.size()) {
				domain = ouList.get(ouCounter++) +",o=CP Indonesia";

				return validate (ldapURL, domain, userName, password);
			}
			// END CODE ANDI - 20180531
			
			if (log.isLoggable(Level.INFO)) log.info("Error: " + principal + " - " + e.getLocalizedMessage());

			ouCounter = 0; // CODE ANDI - 20180531
			
			return false;
		}
		catch (Exception e) 
		{
			log.log (Level.SEVERE, ldapURL + " - " + principal, e);
			
			ouCounter = 0; // CODE ANDI - 20180531
			
		    return false;
		}
		if (log.isLoggable(Level.INFO)) log.info("OK: " + principal);
		
		System.out.println("\n\n >>> Succeed Connect With Domain : "+ domain); // CODE ANDI - 20180531
		ouCounter = 0; // CODE ANDI - 20180531
		
		return true;
	}	//	validate
	
	private static void getOUList(Hashtable<String,String> env) {
		
		if(ouList.size() == 0) {
			DirContext ctx = null;
			try {
				ctx = new InitialDirContext(env);
			} catch (NamingException e1) {
				e1.printStackTrace();
			}
	  
	        SearchControls constraints = new SearchControls();
	        constraints.setSearchScope(SearchControls.SUBTREE_SCOPE);
	
	        try {
				// NamingEnumeration results = ctx.search(MY_SEARCHBASE, MY_FILTER, constraints);
	        	NamingEnumeration<?> results = ctx.search("", "(objectclass=*)", constraints);
				
		        while(results != null && results.hasMore()) {
		            SearchResult sr = (SearchResult)results.next();
		            String dn = sr.getName();
		            
		            int ou_index = dn.indexOf("ou=");
		            int o_index = dn.indexOf(",o=");
		            if( (ou_index >= 0) && (o_index >= 0) ) {
		            	String ou = dn.substring(ou_index, o_index);
		            	
	    	            if(!ouList.contains( ou )) {
	    	            	ouList.add(ou);
	    	            }
		            }
		        }
			} catch (NamingException e1) {
				e1.printStackTrace();
			}
	        
	        System.out.println("\n >>> LIST OU : " + ouList.toString());
		}
		
		return ;
	}
	
	/**	Logger	*/
	private static final CLogger log = CLogger.getCLogger (LDAP.class);
		
	/**
	 * 	Print Attributes to System.out
	 *	@param attrs
	 */
	 @SuppressWarnings("unused")
	private static void dump (Attributes attrs)
	{
		if (attrs == null)
		{
			System.out.println ("No attributes");
		}
		else
		{
			/* Print each attribute */
			try
			{
				for (NamingEnumeration<? extends Attribute> ae = attrs.getAll (); ae.hasMore ();)
				{
					Attribute attr = ae.next ();
					System.out.println ("attribute: " + attr.getID ());
					/* print each value */
					for (NamingEnumeration<?> e = attr.getAll(); 
						e.hasMore (); 
						System.out.println ("    value: " + e.next()))
						;
				}
			}
			catch (NamingException e)
			{
				e.printStackTrace ();
			}
		}
	}	//	dump		
}	//	LDAP

