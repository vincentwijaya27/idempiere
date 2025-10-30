/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved.                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA        *
 * or via info@compiere.org or http://www.compiere.org/license.html           *
 *****************************************************************************/
package org.compiere.acct;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

import org.compiere.model.MAccount;
import org.compiere.model.MAcctSchema;
import org.compiere.model.MAcctSchemaElement;
import org.compiere.model.MDistribution;
import org.compiere.model.MDistributionLine;
import org.compiere.model.MElementValue;
import org.compiere.model.MFactAcct;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoiceLine;
import org.compiere.model.MJournal;
import org.compiere.model.MJournalLine;
import org.compiere.model.MMatchInv;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;

/**
 *  Accounting Fact for {@link Doc}.<br/>
 *  Create and save one or more {@link FactLine} for an accounting document.
 *
 *  @author 	Jorg Janke
 *  @version 	$Id: Fact.java,v 1.2 2006/07/30 00:53:33 jjanke Exp $
 *  
 *  BF [ 2789949 ] Multicurrency in matching posting
 */
public final class Fact
{
	/**
	 *	Constructor
	 *  @param  document    pointer to document
	 *  @param  acctSchema  Account Schema to create accounts
	 *  @param  defaultPostingType  the default Posting type (actual,..) for this posting
	 */
	public Fact (Doc document, MAcctSchema acctSchema, String defaultPostingType)
	{
		m_doc = document;
		m_acctSchema = acctSchema;
		m_postingType = defaultPostingType;
		// Fix [ 1884676 ] Fact not setting transaction
		m_trxName = document.getTrxName();
		//
		if (log.isLoggable(Level.CONFIG)) log.config(toString());
	}	//	Fact

	/**	Log					*/
	private static final CLogger	log = CLogger.getCLogger(Fact.class);

	/** Document            */
	private Doc             m_doc = null;
	/** Accounting Schema   */
	private MAcctSchema	    m_acctSchema = null;
	/** Transaction			*/
	private String m_trxName;

	/** Posting Type        */
	private String		    m_postingType = null;

	/** Actual Balance Type */
	public static final String	POST_Actual = MFactAcct.POSTINGTYPE_Actual;
	/** Budget Balance Type */
	public static final String	POST_Budget = MFactAcct.POSTINGTYPE_Budget;
	/** Encumbrance Posting */
	public static final String	POST_Commitment = MFactAcct.POSTINGTYPE_Commitment;
	/** Encumbrance Posting */
	public static final String	POST_Reservation = MFactAcct.POSTINGTYPE_Reservation;

	/** Is Converted        */
	private boolean		    m_converted = false;

	/** Lines               */
	private ArrayList<FactLine>	m_lines = new ArrayList<FactLine>();

	private ArrayList<FactLine>	FactLineToRemove = new ArrayList<FactLine>();

	/**
	 *  Dispose
	 */
	public void dispose()
	{
		m_lines.clear();
		m_lines = null;
	}   //  dispose

	/**
	 *	Create and convert Fact Line.
	 *  Used to create a DR and/or CR entry
	 *
	 *	@param  docLine     the document line or null
	 *  @param  account     if null, line is not created
	 *  @param  C_Currency_ID   the currency
	 *  @param  debitAmt    debit amount, can be null
	 *  @param  creditAmt  credit amount, can be null
	 *  @return Fact Line
	 */
	public FactLine createLine (DocLine docLine, MAccount account,
		int C_Currency_ID, BigDecimal debitAmt, BigDecimal creditAmt)
	{
		//  Data Check
		if (account == null)
		{
			if (log.isLoggable(Level.INFO)) log.info("No account for " + docLine 
				+ ": Amt=" + debitAmt + "/" + creditAmt 
				+ " - " + toString());			
			return null;
		}
		//
		FactLine line = new FactLine (m_doc.getCtx(), m_doc.get_Table_ID(), 
			m_doc.get_ID(),
			docLine == null ? 0 : docLine.get_ID(), m_trxName);
		//  Set Info & Account
		line.setDocumentInfo(m_doc, docLine);
		line.setPostingType(m_postingType);
		line.setAccount(m_acctSchema, account);

		//  Amounts - one needs to not zero
		if (!line.setAmtSource(C_Currency_ID, debitAmt, creditAmt))
		{
			if (docLine == null || docLine.getQty() == null || docLine.getQty().signum() == 0)
			{
				if (log.isLoggable(Level.FINE)) log.fine("Both amounts & qty = 0/Null - " + docLine		
					+ " - " + toString());			
				return null;
			}
			if (log.isLoggable(Level.FINE)) log.fine("Both amounts = 0/Null, Qty=" + docLine.getQty() + " - " + docLine		
				+ " - " + toString());			
		}
		//  Convert
		line.convert();
		//  Optionally overwrite Acct Amount
		if (docLine != null 
			&& (docLine.getAmtAcctDr() != null || docLine.getAmtAcctCr() != null))
			line.setAmtAcct(docLine.getAmtAcctDr(), docLine.getAmtAcctCr());
		//
		
		// BEGIN CODE ANDI - 20190909 - Pada Juournal discount, akan di tambahkan id product nya - request by Ci Sin
//		System.out.println("\n\n >>> account : " + account.getAccount_ID());
		String DocumentNo = DB.getSQLValueString(get_TrxName(), "SELECT DocumentNo FROM "+ m_doc.get_TableName() +" WHERE AD_Client_ID = ? AND "+ m_doc.get_TableName()+"_ID = ?", m_doc.getAD_Client_ID(), m_doc.get_ID());
		String tablename = DB.getSQLValueString(get_TrxName(), "SELECT tablename FROM AD_Table WHERE AD_Table_ID = ?", line.getAD_Table_ID());
//		System.out.println("\n\n >>> fact acct for tablename : " + tablename);
//		System.out.println("\n\n >>> DocumentNo : " + DocumentNo);
		if( docLine != null && account.getAccountType().equalsIgnoreCase("R") && (tablename.equalsIgnoreCase("C_Invoice") || tablename.equalsIgnoreCase("M_Inout")) ) {
			int M_Product_ID = DB.getSQLValue(get_TrxName(), "SELECT M_Product_ID FROM "+tablename+"line d WHERE d."+tablename+"line_ID = ?", docLine.get_ID());
			if(M_Product_ID > 0) {
				line.setM_Product_ID(M_Product_ID);
			}
		}
		// END CODE ANDI - 20190909 
		
// BEGIN CODE JACKSON - 20190913 - Menambahkan DocStatus & Document No pada Fact Accounting - request by Ci Sin
		String DocStatus = "??";
		int AD_ColumnDocStatus_ID = DB.getSQLValue(get_TrxName(), "SELECT AD_Column_ID FROM AD_Column WHERE AD_Table_ID = (SELECT AD_Table_ID FROM AD_Table WHERE LOWER(TableName) = '"+ m_doc.get_TableName().toLowerCase() +"' AND LOWER(ColumnName) = 'docstatus')");
		if(AD_ColumnDocStatus_ID > 0) {
			int C_DocType_MI_ID = DB.getSQLValue(get_TrxName(), "SELECT C_DocType_ID FROM C_DocType WHERE AD_Client_ID = ? AND Name = 'Match Invoice'", + m_doc.getAD_Client_ID());
			if(m_doc.getDescription() != null) {
				Boolean isReversed = m_doc.getDescription().contains("Invoice: ") || m_doc.getDescription().contains("Payment: ") || m_doc.getDescription().contains("->") || m_doc.getDescription().contains("<-") ? true : false;
				if(isReversed) {
					DocStatus = "RE";
					int Reversal_ID = DB.getSQLValue(get_TrxName(), "SELECT Reversal_ID FROM "+ m_doc.get_TableName() +" WHERE AD_Client_ID = ? AND "+ m_doc.get_TableName()+"_ID = ?", m_doc.getAD_Client_ID(), m_doc.get_ID());
					DB.executeUpdateEx("UPDATE Fact_Acct SET DocStatus = '"+ DocStatus +"' WHERE AD_Client_ID = "+ m_doc.getAD_Client_ID() +" and Record_ID = "+ Reversal_ID +" AND AD_Table_ID = "+ m_doc.get_Table_ID(), get_TrxName());
				}
				else
					DocStatus = DB.getSQLValueString(get_TrxName(), "SELECT DocStatus FROM "+ m_doc.get_TableName() +" WHERE AD_Client_ID = ? AND "+ m_doc.get_TableName()+"_ID = ?", m_doc.getAD_Client_ID(), m_doc.get_ID());
			}else {
				if(m_doc.getC_DocType_ID() == C_DocType_MI_ID) {
					MMatchInv objMMi = (MMatchInv) m_doc.getPO();
					int C_Invoice_ID = DB.getSQLValue(get_TrxName(), "SELECT C_Invoice_ID FROM C_InvoiceLine WHERE AD_Client_ID = ? AND C_InvoiceLine_ID = ?"
							, + m_doc.getAD_Client_ID(), objMMi.getC_InvoiceLine_ID());
					DocStatus = DB.getSQLValueString(get_TrxName(), "SELECT DocStatus FROM C_Invoice WHERE AD_Client_ID = ? AND C_Invoice_ID = ?"
							, + m_doc.getAD_Client_ID(), C_Invoice_ID);
				}
				else	
					DocStatus = DB.getSQLValueString(get_TrxName(), "SELECT DocStatus FROM "+ m_doc.get_TableName() +" WHERE AD_Client_ID = ? AND "+ m_doc.get_TableName()+"_ID = ?", + m_doc.getAD_Client_ID(), m_doc.get_ID());
			}
		}

		line.set_ValueOfColumn("DocStatus", DocStatus);
		line.set_ValueOfColumn("DocumentNo", DocumentNo);
		// END CODE JACKSON - 20190913
			
		// BEGIN CODE JACKSON - 20191220 -- #1192 : Accounting fact tambah target document type - Request By Ci Sin
		int AD_ColumnDocType_ID = DB.getSQLValue(get_TrxName(), "SELECT AD_Column_ID FROM AD_Column WHERE AD_Table_ID = (SELECT AD_Table_ID FROM AD_Table WHERE LOWER(TableName) = '"+ m_doc.get_TableName().toLowerCase() +"' AND LOWER(ColumnName) = 'c_doctype_id')");
		int AD_ColumnDocTypeTarget_ID = DB.getSQLValue(get_TrxName(), "SELECT AD_Column_ID FROM AD_Column WHERE AD_Table_ID = (SELECT AD_Table_ID FROM AD_Table WHERE LOWER(TableName) = '"+ m_doc.get_TableName().toLowerCase() +"' AND LOWER(ColumnName) = 'c_doctypetarget_id')");
		if(AD_ColumnDocType_ID > 0 || AD_ColumnDocTypeTarget_ID > 0) {
			if(AD_ColumnDocType_ID > 0) {
				int C_DocType_ID = DB.getSQLValue(get_TrxName(), "SELECT C_DocType_ID FROM "+ m_doc.get_TableName() +" WHERE AD_Client_ID = ? AND "+ m_doc.get_TableName()+"_ID = ?", m_doc.getAD_Client_ID(), m_doc.get_ID());
				line.set_ValueOfColumn("C_DocType_ID", C_DocType_ID);
			}else {
				int C_DocType_ID = DB.getSQLValue(get_TrxName(), "SELECT C_DocTypeTarget_ID FROM "+ m_doc.get_TableName() +" WHERE AD_Client_ID = ? AND "+ m_doc.get_TableName()+"_ID = ?", m_doc.getAD_Client_ID(), m_doc.get_ID());
				line.set_ValueOfColumn("C_DocType_ID", C_DocType_ID);
			}
			
		}
		// END CODE JACKSON - 20191220
		
		// BEGIN CODE JACKSON - 20200326 : #1398, #1399 - Menambahkan No. Polisi pada Accounting Fact Detail (APR) pada saat Generate Invoice & GL Journal
		String ClientName = DB.getSQLValueString(get_TrxName(), "SELECT Name FROM AD_Client WHERE AD_Client_ID = ?", m_doc.getAD_Client_ID());
		if(ClientName.equalsIgnoreCase("APR")) { // ADDED BY JACKSON - 20200504 : Revisi Code pengambilan No. Polisi dari Invoice > Asset menjadi Invoice > Project (IKB) > Asset *Khusus APR* (Reques By Ci Sin)
			if(tablename.equalsIgnoreCase("C_Invoice")) {
				MInvoice invoice = new MInvoice(m_doc.getCtx(), line.getRecord_ID(), get_TrxName());
				// String DocTypeName = DB.getSQLValueString(get_TrxName(), "SELECT Name FROM C_DocType WHERE C_DocType_ID = ?", invoice.getC_DocTypeTarget_ID());
				
				if(invoice.get_Value("C_Project_ID") != null && invoice.get_ValueAsInt("C_Project_ID") > 0) {
					int A_Asset_ID = DB.getSQLValue(get_TrxName(), "SELECT A_Asset_ID FROM C_Project WHERE C_Project_ID = ?", invoice.get_ValueAsInt("C_Project_ID"));
					String NoPolisi = DB.getSQLValueString(get_TrxName(), "SELECT SerNo FROM A_Asset WHERE A_Asset_ID = ?", A_Asset_ID);
					if(NoPolisi != null) {
						line.set_ValueOfColumn("SerNo", NoPolisi);
					}
				}

				// BEGIN CODE JACKSON - 20200422 : #1498 - Charge di memo munculkan Product dan muncul di acct jurnal
				if(invoice.get_Value("A_Asset_ID") != null && invoice.get_ValueAsInt("A_Asset_ID") > 0) {
					String NoPolisi = DB.getSQLValueString(get_TrxName(), "SELECT SerNo FROM A_Asset WHERE A_Asset_ID = ?", invoice.get_ValueAsInt("A_Asset_ID"));
					if(NoPolisi != null) {
						line.set_ValueOfColumn("SerNo", NoPolisi);
					}
				}
				// END CODE JACKSON - 20200422

				// BEGIN CODE JACKSON - 20200422 : #1504 - tambahkan inputan no polisi di invoice vendor
				if(docLine != null) {
					MInvoiceLine invoiceLn = new MInvoiceLine(m_doc.getCtx(), docLine.get_ID(), get_TrxName());
					if(invoiceLn != null && invoiceLn.get_Value("z_nopolisi") != null)
						line.set_ValueOfColumn("SerNo", invoiceLn.get_Value("z_nopolisi"));
				}
				// END CODE JACKSON - 20200422
			} else if(tablename.equalsIgnoreCase("GL_Journal")) {
				MJournalLine journalLine = new MJournalLine(m_doc.getCtx(), line.getLine_ID(), get_TrxName());
				String NoPolisi = journalLine.get_ValueAsString("SerNo");
				if(NoPolisi != null) {
					line.set_ValueOfColumn("SerNo", NoPolisi);
				}
			}
		} else if(ClientName.equalsIgnoreCase("Plantation")) { // ADDED BY JACKSON - 20201126 : #2550 - GL journal tambah field Divisi di header (ambil dari Blok di line)
			if(tablename.equalsIgnoreCase("GL_Journal")) {
				MJournal journal = new MJournal(m_doc.getCtx(), line.getRecord_ID(), get_TrxName());
				
				if(journal.get_Value("Z_Division_ID") != null && journal.get_ValueAsInt("Z_Division_ID") >= 0) {
					line.set_ValueOfColumn("Z_Division_ID", journal.get_ValueAsInt("Z_Division_ID"));
				}
				
				if(docLine != null) {
					MJournalLine journalLn = new MJournalLine(m_doc.getCtx(), docLine.get_ID(), get_TrxName());
					if(journalLn != null ) {
						if(journalLn.getUser2_ID() > 0) {
							Timestamp z_thn_tnm = DB.getSQLValueTS(get_TrxName(), "SELECT z_thn_tnm FROM Z_Tbl_Blok WHERE AD_Client_ID = ? AND C_ElementValue_ID = ?", m_doc.getAD_Client_ID(), journalLn.getUser2_ID());
							if(z_thn_tnm != null) {
								DateFormat dateFormatYear = new SimpleDateFormat("yyyy");
								line.set_ValueOfColumn("z_thn_tnm", dateFormatYear.format(z_thn_tnm));
							}
							int Z_Tbl_Tpk_ID= DB.getSQLValue(get_TrxName(), "SELECT Z_Tbl_Tpk_ID FROM Z_Tbl_Blok WHERE AD_Client_ID = ? AND C_ElementValue_ID = ?", m_doc.getAD_Client_ID(), journalLn.getUser2_ID());
							if(Z_Tbl_Tpk_ID > 0) {
								line.set_ValueOfColumn("Z_Tbl_Tpk_ID", Z_Tbl_Tpk_ID);
							}
						} 

						// BEGIN CODE JACKSON - 20210105 : #2751
						if(journalLn.get_ValueAsString("SerNo") != null) {
							String NoPolisi = journalLn.get_ValueAsString("SerNo");
							line.set_ValueOfColumn("SerNo", NoPolisi);
							
							int Z_PIC_ID = DB.getSQLValue(get_TrxName(), "SELECT Z_PIC_ID FROM A_Asset WHERE AD_Client_ID = ? AND SerNo = ? AND Z_PIC_ID > 0", journalLn.getAD_Client_ID(), NoPolisi);
							if(Z_PIC_ID <= 0) {
								Z_PIC_ID = DB.getSQLValue(get_TrxName(), "SELECT Z_PIC_ID FROM A_Asset WHERE AD_Client_ID = ? AND Name = ? AND Z_PIC_ID > 0", journalLn.getAD_Client_ID(), NoPolisi);
							}
						}
						
						if(journalLn.get_ValueAsInt("Z_Tbl_Tpk_ID") > 0) {
							line.set_ValueOfColumn("Z_Tbl_Tpk_ID", journalLn.get_ValueAsInt("Z_Tbl_Tpk_ID"));
						}

						if(journalLn.get_Value("z_thn_tnm") != null) {
							line.set_ValueOfColumn("z_thn_tnm", journalLn.get_Value("z_thn_tnm"));
						}
						// END CODE JACKSON - 20210105
					}
				}
			}
		}
		// END CODE JACKSON - 20200326
		
		if (log.isLoggable(Level.FINE)) log.fine(line.toString());
		add(line);
		return line;
	}	//	createLine

	/**
	 *  Add Fact Line
	 *  @param line fact line
	 */
	public void add (FactLine line)
	{
		m_lines.add(line);
	}   //  add

	/**
	 *  Remove Fact Line
	 *  @param line fact line
	 */
	public void remove (FactLine line)
	{
		m_lines.remove(line);
	}   //  remove

	/**
	 *	Create and convert Fact Line.
	 *  Used to create either a DR or CR entry
	 *
	 *	@param  docLine     Document Line or null
	 *  @param  accountDr   Account to be used if Amt is DR balance
	 *  @param  accountCr   Account to be used if Amt is CR balance
	 *  @param  C_Currency_ID Currency
	 *  @param  Amt if negative Cr else Dr
	 *  @return FactLine
	 */
	public FactLine createLine (DocLine docLine, MAccount accountDr, MAccount accountCr,
		int C_Currency_ID, BigDecimal Amt)
	{
		if (Amt.signum() < 0)
			return createLine (docLine, accountCr, C_Currency_ID, null, Amt.abs());
		else
			return createLine (docLine, accountDr, C_Currency_ID, Amt, null);
	}   //  createLine

	/**
	 *	Create and convert Fact Line.
	 *  Used to create either a DR or CR entry
	 *
	 *	@param  docLine Document line or null
	 *  @param  account   Account to be used
	 *  @param  C_Currency_ID Currency
	 *  @param  Amt if negative Cr else Dr
	 *  @return FactLine
	 */
	public FactLine createLine (DocLine docLine, MAccount account,
		int C_Currency_ID, BigDecimal Amt)
	{
		if (Amt.signum() < 0)
			return createLine (docLine, account, C_Currency_ID, null, Amt.abs());
		else
			return createLine (docLine, account, C_Currency_ID, Amt, null);
	}   //  createLine

	/**
	 *  Is Posting Type
	 *  @param  PostingType - see POST_*
	 *  @return true if document is posting type
	 */
	public boolean isPostingType (String PostingType)
	{
		return m_postingType.equals(PostingType);
	}   //  isPostingType

	/**
	 *	Is converted
	 *  @return true if converted
	 */
	public boolean isConverted()
	{
		return m_converted;
	}	//	isConverted

	/**
	 *	Get AcctSchema
	 *  @return AcctSchema
	 */
	public MAcctSchema getAcctSchema()
	{
		return m_acctSchema;
	}	//	getAcctSchema
	
	/**
	 *	Are the lines Source Balanced
	 *  @return true if source lines balanced
	 */
	public boolean isSourceBalanced()
	{
		//AZ Goodwill
		//  Multi-Currency documents are source balanced by definition
		//  No lines -> balanced
		if (m_lines.size() == 0 || m_doc.isMultiCurrency())
			return true;
		
		// If there is more than 1 currency in fact lines, it is a multi currency doc
		ArrayList<Integer> list = new ArrayList<Integer>();
		for (int i = 0; i < m_lines.size(); i++){
			FactLine line = (FactLine)m_lines.get(i);
			if (line.getC_Currency_ID() > 0 && !list.contains(line.getC_Currency_ID()))
				list.add(line.getC_Currency_ID());
	
		}
		if (list.size() > 1 )
			return true;
				
		BigDecimal balance = getSourceBalance();
		boolean retValue = balance.signum() == 0;
		if (retValue) {
			if (log.isLoggable(Level.FINER)) log.finer(toString());
		} else {
			log.warning ("NO - Diff=" + balance + " - " + toString());
		}
		return retValue;
	}	//	isSourceBalanced

	/**
	 *	Get Source Balance Amount
	 *  @return source balance
	 */
	protected BigDecimal getSourceBalance()
	{
		BigDecimal result = Env.ZERO;
		for (int i = 0; i < m_lines.size(); i++)
		{
			FactLine line = (FactLine)m_lines.get(i);
			result = result.add (line.getSourceBalance());
		}
	//	log.fine("getSourceBalance - " + result.toString());
		return result;
	}	//	getSourceBalance

	/**
	 *	Create Source Line for Suspense Balancing.<br/>
	 *  Only if Suspense Balancing is enabled and not a multi-currency document
	 *  (double check as, otherwise the rule should not have fired). <br/>
	 *  If not balanced, create balancing entry in currency of the document.
	 *  @return Balancing FactLine or null
	 */
	public FactLine balanceSource()
	{
		if (!m_acctSchema.isSuspenseBalancing() || m_doc.isMultiCurrency())
			return null;
		BigDecimal diff = getSourceBalance();
		if (log.isLoggable(Level.FINER)) log.finer("Diff=" + diff);

		//  new line
		FactLine line = new FactLine (m_doc.getCtx(), m_doc.get_Table_ID(), 
			m_doc.get_ID(), 0, m_trxName);
		line.setDocumentInfo(m_doc, null);
		line.setAD_Org_ID(m_doc.getAD_Org_ID());
		line.setPostingType(m_postingType);

		//	Account
		line.setAccount(m_acctSchema, m_acctSchema.getSuspenseBalancing_Acct());

		//  Amount
		if (diff.signum() < 0)   //  negative balance => DR
			line.setAmtSource(m_doc.getC_Currency_ID(), diff.abs(), Env.ZERO);
		else                                //  positive balance => CR
			line.setAmtSource(m_doc.getC_Currency_ID(), Env.ZERO, diff);
			
		//  Convert
		line.convert();
		//
		if (log.isLoggable(Level.FINE)) log.fine(line.toString());
		m_lines.add(line);
		return line;
	}   //  balancingSource
	
	/**
	 *  Are all segments balanced
	 *  @return true if segments are balanced
	 */
	public boolean isSegmentBalanced()
	{
		//  No lines -> balanced
		if (m_lines.size() == 0)
			return true;
		
		MAcctSchemaElement[] elements = m_acctSchema.getAcctSchemaElements();
		//  check all balancing segments
		for (int i = 0; i < elements.length; i++)
		{
			MAcctSchemaElement ase = elements[i];
			if (ase.isBalanced() && !isSegmentBalanced (ase.getElementType()))
				return false;
		}
		return true;
	}   //  isSegmentBalanced

	/**
	 *  Is Source Segment balanced.
	 *  @param  segmentType - see AcctSchemaElement.SEGMENT_*.<br/>
	 *  Implemented only for Org.
	 *  Other sensible candidates are Project, User1/2.
	 *  @return true if segments are balanced
	 */
	public boolean isSegmentBalanced (String segmentType)
	{
		if (segmentType.equals(MAcctSchemaElement.ELEMENTTYPE_Organization))
		{
			HashMap<Integer,BigDecimal> map = new HashMap<Integer,BigDecimal>();
			//  Add up values by organization
			for (int i = 0; i < m_lines.size(); i++)
			{
				FactLine line = (FactLine)m_lines.get(i);
				Integer key = Integer.valueOf(line.getAD_Org_ID());
				BigDecimal bal = line.getSourceBalance();
				BigDecimal oldBal = (BigDecimal)map.get(key);
				if (oldBal != null)
					bal = bal.add(oldBal);
				map.put(key, bal);
			}
			
			//  check if there are not balance entries involving multiple organizations
			Map<Integer, BigDecimal> notBalance = new HashMap<>();			
			for(Map.Entry<Integer, BigDecimal> entry : map.entrySet())
			{
				BigDecimal bal = entry.getValue();
				if (bal.signum() != 0)
				{
					notBalance.put(entry.getKey(), entry.getValue());
				}
			}
			
			if (notBalance.size() > 1)
			{
				return false;
			}
			
			if (log.isLoggable(Level.FINER)) log.finer("(" + segmentType + ") - " + toString());
			return true;
		}
		if (log.isLoggable(Level.FINER)) log.finer("(" + segmentType + ") (not checked) - " + toString());
		return true;
	}   //  isSegmentBalanced

	/**
	 *  Balance all segments.
	 *  - For all balancing segments
	 *      - For all segment values
	 *          - If balance &lt;&gt; 0 create dueTo/dueFrom line
	 *              overwriting the segment value
	 */
	public void balanceSegments()
	{
		MAcctSchemaElement[] elements = m_acctSchema.getAcctSchemaElements();
		//  check all balancing segments
		for (int i = 0; i < elements.length; i++)
		{
			MAcctSchemaElement ase = elements[i];
			if (ase.isBalanced())
				balanceSegment (ase.getElementType());
		}
	}   //  balanceSegments

	/**
	 *  Balance Source Segment
	 *  @param elementType segment element type
	 */
	private void balanceSegment (String elementType)
	{
		//  no lines -> balanced
		if (m_lines.size() == 0)
			return;

		if (log.isLoggable(Level.FINE)) log.fine ("(" + elementType + ") - " + toString());

		//  Org
		if (elementType.equals(MAcctSchemaElement.ELEMENTTYPE_Organization))
		{
			HashMap<Integer,Balance> map = new HashMap<Integer,Balance>();
			//  Add up values by key
			for (int i = 0; i < m_lines.size(); i++)
			{
				FactLine line = (FactLine)m_lines.get(i);
				Integer key = Integer.valueOf(line.getAD_Org_ID());
				Balance oldBalance = (Balance)map.get(key);
				if (oldBalance == null)
				{
					oldBalance = new Balance (line.getAmtSourceDr(), line.getAmtSourceCr());
					map.put(key, oldBalance);
				}
				else
					oldBalance.add(line.getAmtSourceDr(), line.getAmtSourceCr());
			}

			//  Create entry for non-zero element
			Iterator<Integer> keys = map.keySet().iterator();
			while (keys.hasNext())
			{
				Integer key = keys.next();
				Balance difference = map.get(key);
				if (log.isLoggable(Level.INFO)) log.info (elementType + "=" + key + ", " + difference);
				//
				if (!difference.isZeroBalance())
				{
					//  Create Balancing Entry
					FactLine line = new FactLine (m_doc.getCtx(), m_doc.get_Table_ID(), 
						m_doc.get_ID(), 0, m_trxName);
					line.setDocumentInfo(m_doc, null);
					line.setPostingType(m_postingType);
					//  Amount & Account
					if (difference.getBalance().signum() < 0)
					{
						if (difference.isReversal())
						{
							line.setAccount(m_acctSchema, m_acctSchema.getDueTo_Acct(elementType));
							line.setAmtSource(m_doc.getC_Currency_ID(), Env.ZERO, difference.getPostBalance());
						}
						else
						{
							line.setAccount(m_acctSchema, m_acctSchema.getDueFrom_Acct(elementType));
							line.setAmtSource(m_doc.getC_Currency_ID(), difference.getPostBalance(), Env.ZERO);
						}
					}
					else
					{
						if (difference.isReversal())
						{
							line.setAccount(m_acctSchema, m_acctSchema.getDueFrom_Acct(elementType));
							line.setAmtSource(m_doc.getC_Currency_ID(), difference.getPostBalance(), Env.ZERO);
						}
						else
						{
							line.setAccount(m_acctSchema, m_acctSchema.getDueTo_Acct(elementType));
							line.setAmtSource(m_doc.getC_Currency_ID(), Env.ZERO, difference.getPostBalance());
						}
					}
					line.convert();
					line.setAD_Org_ID(key.intValue());
					//
					m_lines.add(line);
					if (log.isLoggable(Level.FINE)) log.fine("(" + elementType + ") - " + line);
				}
			}
			map.clear();
		}
	}   //  balanceSegment
	
	/**
	 *	Are the lines Accounting Balanced
	 *  @return true if accounting lines are balanced
	 */
	public boolean isAcctBalanced()
	{
		//  no lines -> balanced
		if (m_lines.size() == 0)
			return true;
		BigDecimal balance = getAcctBalance();
		boolean retValue = balance.signum() == 0;
		if (retValue) {
			if (log.isLoggable(Level.FINER)) log.finer(toString());
		} else {
			log.warning("NO - Diff=" + balance + " - " + toString());
		}
		return retValue;
	}	//	isAcctBalanced

	/**
	 *	Return Accounting Balance
	 *  @return true if accounting lines are balanced
	 */
	protected BigDecimal getAcctBalance()
	{
		BigDecimal result = Env.ZERO;
		for (int i = 0; i < m_lines.size(); i++)
		{
			FactLine line = (FactLine)m_lines.get(i);
			result = result.add(line.getAcctBalance());
		}
		return result;
	}	//	getAcctBalance

	/**
	 *  Balance Accounting Currency.
	 *  <pre>
	 *  If the accounting currency is not balanced,
	 *      if Currency balancing is enabled
	 *          create a new line using the currency balancing account with zero source balance
	 *      or
	 *          adjust the line with the largest balance sheet account
	 *          or if no balance sheet account exist, the line with the largest amount
	 *  </pre>
	 *  @return FactLine
	 */
	public FactLine balanceAccounting()
	{
		BigDecimal diff = getAcctBalance();		//	DR-CR
		if (log.isLoggable(Level.FINE)) log.fine("Balance=" + diff 
			+ ", CurrBal=" + m_acctSchema.isCurrencyBalancing() 
			+ " - " + toString());
		FactLine line = null;

		BigDecimal BSamount = Env.ZERO;
		FactLine BSline = null;
		BigDecimal PLamount = Env.ZERO;
		FactLine PLline = null;

		//  Find line biggest BalanceSheet or P&L line
		for (int i = 0; i < m_lines.size(); i++)
		{
			FactLine l = (FactLine)m_lines.get(i);
			BigDecimal amt = l.getAcctBalance().abs();
			if (l.isBalanceSheet() && amt.compareTo(BSamount) > 0)
			{
				BSamount = amt;
				BSline = l;
			}
			else if (!l.isBalanceSheet() && amt.compareTo(PLamount) > 0)
			{
				PLamount = amt;
				PLline = l;
			}
		}
		
		//  Create Currency Balancing Entry
		if (m_acctSchema.isCurrencyBalancing())
		{
			line = new FactLine (m_doc.getCtx(), m_doc.get_Table_ID(), 
				m_doc.get_ID(), 0, m_trxName);
			line.setDocumentInfo (m_doc, null);
			line.setPostingType (m_postingType);
			line.setAD_Org_ID(m_doc.getAD_Org_ID());
			line.setAccount (m_acctSchema, m_acctSchema.getCurrencyBalancing_Acct());
			
			//  Amount
			line.setAmtSource(m_doc.getC_Currency_ID(), Env.ZERO, Env.ZERO);
			line.convert();
			//	Accounted
			BigDecimal drAmt = Env.ZERO;
			BigDecimal crAmt = Env.ZERO;
			boolean isDR = diff.signum() < 0;
			BigDecimal difference = diff.abs();
			if (isDR)
				drAmt = difference;
			else
				crAmt = difference;
			//	Switch sides
			boolean switchIt = BSline != null 
				&& ((BSline.isDrSourceBalance() && isDR)
					|| (!BSline.isDrSourceBalance() && !isDR));
			if (switchIt)
			{
				drAmt = Env.ZERO;
				crAmt = Env.ZERO;
				if (isDR)
					crAmt = difference.negate();
				else
					drAmt = difference.negate();
			}
			line.setAmtAcct(drAmt, crAmt);
			if (log.isLoggable(Level.FINE)) log.fine(line.toString());
			m_lines.add(line);
		}
		else	//  Adjust biggest (Balance Sheet) line amount
		{
			if (BSline != null)
				line = BSline;
			else
				line = PLline;
			if (line == null)
				log.severe ("No Line found");
			else
			{
				if (log.isLoggable(Level.FINE)) log.fine("Adjusting Amt=" + diff + "; Line=" + line);
				line.currencyCorrect(diff);
				if (log.isLoggable(Level.FINE)) log.fine(line.toString());
			}
		}   //  correct biggest amount

		return line;
	}   //  balanceAccounting

	/**
	 * 	Check Accounts of Fact Lines
	 *	@return true if success
	 */
	public boolean checkAccounts()
	{
		//  no lines -> nothing to distribute
		if (m_lines.size() == 0)
			return true;
		
		//	For all fact lines
		for (int i = 0; i < m_lines.size(); i++)
		{
			FactLine line = (FactLine)m_lines.get(i);
			MAccount account = line.getAccount();
			if (account == null)
			{
				log.warning("No Account for " + line);
				return false;
			}
			MElementValue ev = account.getAccount();
			if (ev == null)
			{
				log.warning("No Element Value for " + account 
					+ ": " + line);
				m_doc.p_Error = account.toString();
				return false;
			}
			if (ev.isSummary())
			{
				log.warning("Cannot post to Summary Account " + ev 
					+ ": " + line);
				m_doc.p_Error = ev.toString();
				return false;
			}
			if (!ev.isActive())
			{
				log.warning("Cannot post to Inactive Account " + ev 
					+ ": " + line);
				m_doc.p_Error = ev.toString();
				return false;
			}

		}	//	for all lines
		
		return true;
	}	//	checkAccounts
	
	/**
	 * 	GL Distribution of Fact Lines
	 *	@return true if success
	 */
	public boolean distribute()
	{
		//  no lines -> nothing to distribute
		if (m_lines.size() == 0)
			return true;
		
		ArrayList<FactLine> newLines = new ArrayList<FactLine>();
		//	For all fact lines
		for (int i = 0; i < m_lines.size(); i++)
		{
			FactLine dLine = (FactLine)m_lines.get(i);
			MDistribution[] distributions = MDistribution.get (dLine.getAccount(), 
				m_postingType, m_doc.getC_DocType_ID(), dLine.getDateAcct());
			//	No Distribution for this line
			//AZ Goodwill
			//The above "get" only work in GL Journal because it's using ValidCombination Account
			if (distributions == null || distributions.length == 0)
			{
				distributions = MDistribution.get (dLine.getCtx(), dLine.getC_AcctSchema_ID(),
					m_postingType, m_doc.getC_DocType_ID(), dLine.getDateAcct(),
					dLine.getAD_Org_ID(), dLine.getAccount_ID(),
					dLine.getM_Product_ID(), dLine.getC_BPartner_ID(), dLine.getC_Project_ID(),
					dLine.getC_Campaign_ID(), dLine.getC_Activity_ID(), dLine.getAD_OrgTrx_ID(),
					dLine.getC_SalesRegion_ID(), dLine.getC_LocTo_ID(), dLine.getC_LocFrom_ID(),
					dLine.getUser1_ID(), dLine.getUser2_ID());
				if (distributions == null || distributions.length == 0)
					continue;
			}
			//end AZ
			//	Just the first
			if (distributions.length > 1)
				log.warning("More than one Distribution for " + dLine.getAccount());
			MDistribution distribution = distributions[0];

			// FR 2685367 - GL Distribution delete line instead reverse
			if (distribution.isCreateReversal()) {
				//	Add Reversal
				FactLine reversal = dLine.reverse(distribution.getName());
				if (log.isLoggable(Level.INFO)) log.info("Reversal=" + reversal);
				newLines.add(reversal);		//	saved in postCommit
			} else {
				// delete the line being distributed
				m_lines.remove(i);    // or it could be m_lines.remove(dLine);
				i--;
			}

			//	Prepare
			distribution.distribute(dLine.getAccount(), dLine.getSourceBalance(), dLine.getQty(), dLine.getC_Currency_ID());
			MDistributionLine[] lines = distribution.getLines(false);
			for (int j = 0; j < lines.length; j++)
			{
				MDistributionLine dl = lines[j];
				if (!dl.isActive() || dl.getAmt().signum() == 0)
					continue;
				FactLine factLine = new FactLine (m_doc.getCtx(), m_doc.get_Table_ID(),
					m_doc.get_ID(), dLine.getLine_ID(), m_trxName);
				//  Set Info & Account
				factLine.setDocumentInfo(m_doc, dLine.getDocLine());
				factLine.setDescription(dLine.getDescription());
				factLine.setAccount(m_acctSchema, dl.getAccount());
				factLine.setPostingType(m_postingType);
				if (dl.isOverwriteOrg())	//	set Org explicitly
					factLine.setAD_Org_ID(dl.getOrg_ID());
				else
					factLine.setAD_Org_ID(dLine.getAD_Org_ID());
				// Silvano - freepath - F3P - Bug#2904994 Fact distribtution only overwriting Org
				if(dl.isOverwriteAcct())
					factLine.setAccount_ID(dl.getAccount_ID());
				else
					factLine.setAccount_ID(dLine.getAccount_ID());
				if(dl.isOverwriteActivity())
					factLine.setC_Activity_ID(dl.getC_Activity_ID());
				else
					factLine.setC_Activity_ID(dLine.getC_Activity_ID());
				if(dl.isOverwriteBPartner())
					factLine.setC_BPartner_ID(dl.getC_BPartner_ID());
				else
					factLine.setC_BPartner_ID(dLine.getC_BPartner_ID());
				if(dl.isOverwriteCampaign())
					factLine.setC_Campaign_ID(dl.getC_Campaign_ID());
				else
					factLine.setC_Campaign_ID(dLine.getC_Campaign_ID());
				if(dl.isOverwriteLocFrom())
					factLine.setC_LocFrom_ID(dl.getC_LocFrom_ID());
				else
					factLine.setC_LocFrom_ID(dLine.getC_LocFrom_ID());
				if(dl.isOverwriteLocTo())
					factLine.setC_LocTo_ID(dl.getC_LocTo_ID());
				else
					factLine.setC_LocTo_ID(dLine.getC_LocTo_ID());
				if(dl.isOverwriteOrgTrx())
					factLine.setAD_OrgTrx_ID(dl.getAD_OrgTrx_ID());
				else
					factLine.setAD_OrgTrx_ID(dLine.getAD_OrgTrx_ID());
				if(dl.isOverwriteProduct())
					factLine.setM_Product_ID(dl.getM_Product_ID());
				else
					factLine.setM_Product_ID(dLine.getM_Product_ID());
				if(dl.isOverwriteProject())
					factLine.setC_Project_ID(dl.getC_Project_ID());
				else
					factLine.setC_Project_ID(dLine.getC_Project_ID());
				if(dl.isOverwriteSalesRegion())
					factLine.setC_SalesRegion_ID(dl.getC_SalesRegion_ID());
				else
					factLine.setC_SalesRegion_ID(dLine.getC_SalesRegion_ID());
				if(dl.isOverwriteUser1())				
					factLine.setUser1_ID(dl.getUser1_ID());
				else
					factLine.setUser1_ID(dLine.getUser1_ID());
				if(dl.isOverwriteUser2())				
					factLine.setUser2_ID(dl.getUser2_ID());					
				else
					factLine.setUser2_ID(dLine.getUser2_ID());
				factLine.setUserElement1_ID(dLine.getUserElement1_ID());
				factLine.setUserElement2_ID(dLine.getUserElement2_ID());
				// F3P end
				//
				if (dLine.getAmtAcctCr().signum() != 0) // isCredit
					factLine.setAmtSource(dLine.getC_Currency_ID(), null, dl.getAmt().negate());
				else
					factLine.setAmtSource(dLine.getC_Currency_ID(), dl.getAmt(), null);
				factLine.setQty(dl.getQty());
				//  Convert
				factLine.convert();
				//
				String description = distribution.getName() + " #" + dl.getLine();
				if (dl.getDescription() != null)
					description += " - " + dl.getDescription();
				factLine.addDescription(description);
				
// BEGIN CODE JACKSON - 20200422 : Menambahkan Set SerNo agar tidak hilang apabila sudah di set sebelumnya
				factLine.set_ValueOfColumn("SerNo", dLine.get_Value("SerNo"));
				factLine.set_ValueOfColumn("Z_Cross_Account_ID", dLine.get_Value("Z_Cross_Account_ID"));
// END CODE JACKSOn - 20200422
				//
				if (log.isLoggable(Level.INFO)) log.info(factLine.toString());
				newLines.add(factLine);
			}
		}	//	for all lines
		
		//	Add Lines
		for (int i = 0; i < newLines.size(); i++)
			m_lines.add(newLines.get(i));
		
		return true;
	}	//	distribute	
	
	/**
	 * String representation
	 * @return String
	 */
	public String toString()
	{
		StringBuilder sb = new StringBuilder("Fact[");
		sb.append(m_doc.toString());
		sb.append(",").append(m_acctSchema.toString());
		sb.append(",PostType=").append(m_postingType);
		sb.append("]");
		return sb.toString();
	}	//	toString

	/**
	 *	Get Lines
	 *  @return FactLine Array
	 */
	public FactLine[] getLines()
	{
		FactLine[] temp = new FactLine[m_lines.size()];
		m_lines.toArray(temp);
		return temp;
	}	//	getLines

	/**
	 *  Save Fact Lines
	 *  @param trxName transaction
	 *  @return true if all lines were saved
	 */
	public boolean save (String trxName)
	{
		m_trxName = trxName;
		//  save Lines
		for (int i = 0; i < m_lines.size(); i++)
		{
			FactLine fl = (FactLine)m_lines.get(i);
			if (!fl.save(trxName))  //  abort on first error
				return false;
		}
		return true;
	}   //  commit

	/**
	 * 	Get Transaction Name
	 *	@return trx nam
	 */
	public String get_TrxName() 
	{
		return m_trxName;
	}	//	getTrxName

	/**
	 * 	Set Transaction name
	 * 	@param trxName
	 */
	@SuppressWarnings("unused")
	private void set_TrxName(String trxName) 
	{
		m_trxName = trxName;
	}	//	set_TrxName

	/**
	 * 	Fact Balance Utility
	 *	
	 *  @author Jorg Janke
	 *  @version $Id: Fact.java,v 1.2 2006/07/30 00:53:33 jjanke Exp $
	 */
	public static class Balance
	{
		/**
		 *	@param dr DR
		 *	@param cr CR
		 */
		public Balance (BigDecimal dr, BigDecimal cr)
		{
			DR = dr;
			CR = cr;
		}
		
		/** DR Amount	*/
		public BigDecimal DR = Env.ZERO;
		/** CR Amount	*/
		public BigDecimal CR = Env.ZERO;
		
		/**
		 * 	Add 
		 *	@param dr DR
		 *	@param cr CR
		 */
		public void add (BigDecimal dr, BigDecimal cr)
		{
			DR = DR.add(dr);
			CR = CR.add(cr);
		}
		
		/**
		 * 	Get Balance
		 *	@return balance
		 */
		public BigDecimal getBalance()
		{
			return DR.subtract(CR);
		}	//	getBalance
		
		/**
		 * 	Get Post Balance
		 *	@return absolute balance - negative if reversal
		 */
		public BigDecimal getPostBalance()
		{
			BigDecimal bd = getBalance().abs();
			if (isReversal())
				return bd.negate();
			return bd;
		}	//	getPostBalance

		/**
		 * 	Zero Balance
		 *	@return true if 0
		 */
		public boolean isZeroBalance()
		{
			return getBalance().signum() == 0;
		}	//	isZeroBalance
		
		/**
		 * 	Reversal
		 *	@return true if both DR/CR are negative or zero
		 */
		public boolean isReversal()
		{
			return DR.signum() <= 0 && CR.signum() <= 0;
		}	//	isReversal
		
		/**
		 * 	String Representation
		 *	@return info
		 */
		public String toString ()
		{
			StringBuilder sb = new StringBuilder ("Balance[");
			sb.append ("DR=").append(DR)
				.append ("-CR=").append(CR)
				.append(" = ").append(getBalance())
				.append ("]");
			return sb.toString ();
		} //	toString
		
	}	//	Balance
	
// BEGIN CODE JACKSON - 20200209 : ValidateFactLine() agar menyatukan Fact Line apabila ada Account & Business Partner yang sama 
// *** ( Dengan Metode selalu menambah ke FactLine selanjutnya ) ***
	public void validateFactLine() {
		FactLine flnBefore = null;
		
// Sorting m_lines
		Collections.sort(m_lines, new Comparator<FactLine>(){
			@Override
			public int compare(FactLine fln1, FactLine fln2) {
				
	// Sort by Account_ID agar bisa di looping
				Integer account1 = ((FactLine) fln1).getAccount_ID();
				Integer account2 = ((FactLine) fln2).getAccount_ID();
				Integer sCompAccount = account1.compareTo(account2);
	            
	            if (sCompAccount != 0) {
	                return sCompAccount;
	            } 
	// Sort By Business Partner
	            Integer bp1 = ((FactLine) fln1).getC_BPartner_ID();
	            Integer bp2 = ((FactLine) fln2).getC_BPartner_ID();
				Integer sCompBP = bp1.compareTo(bp2);
				
				if (sCompBP != 0) {
	                return sCompBP;
	            } 
	// Sort By Product
	            Integer prd1 = ((FactLine) fln1).getM_Product_ID();
	            Integer prd2 = ((FactLine) fln2).getM_Product_ID();
				Integer sCompPrd = prd1.compareTo(prd2);
				
				if (sCompPrd != 0) {
	                return sCompPrd;
	            } 
	// Sort By Project
	            Integer prj1 = ((FactLine) fln1).getC_Project_ID();
	            Integer prj2 = ((FactLine) fln2).getC_Project_ID();
				Integer sCompPrj = prj1.compareTo(prj2);
				
				if (sCompPrj != 0) {
	                return sCompPrj;
	            }  
	// Sort By Sales Region
	            Integer sr1 = ((FactLine) fln1).getC_SalesRegion_ID();
	            Integer sr2 = ((FactLine) fln2).getC_SalesRegion_ID();
				Integer sCompSr = sr1.compareTo(sr2);
				
				if (sCompSr != 0) {
	                return sCompSr;
	            }  
	// Sort By Campaign
	            Integer cmpgn1 = ((FactLine) fln1).getC_Campaign_ID();
	            Integer cmpgn2 = ((FactLine) fln2).getC_Campaign_ID();
				Integer sCompCmpgn = cmpgn1.compareTo(cmpgn2);
				
				if (sCompCmpgn != 0) {
	                return sCompCmpgn;
	            } 
	// Sort By Cost Center
	            Integer cc1 = ((FactLine) fln1).getUser1_ID();
	            Integer cc2 = ((FactLine) fln2).getUser1_ID();
	            
				return cc1.compareTo(cc2);
			}
		});
		
		for(FactLine fln : m_lines) {

			String ClientName = DB.getSQLValueString(get_TrxName(), "SELECT Name FROM AD_Client WHERE AD_Client_ID = ?", fln.getAD_Client_ID());
			boolean flag = false;
			int index = m_lines.indexOf(fln);
			
			if(flnBefore == null) {// Kalau flnBefore == null, continue
				flnBefore = fln;
				FactLineToRemove.add(flnBefore);
				continue;
			}
			if(ClientName.equalsIgnoreCase("APR")) { // ADDED BY JACKSON - 20200423 : Menambahkan IF kalau APR ada pengecekan No. Polisi juga
				if(fln.getAccount_ID() == flnBefore.getAccount_ID() && fln.getC_BPartner_ID() == flnBefore.getC_BPartner_ID() 
						&& fln.getM_Product_ID() == flnBefore.getM_Product_ID() && fln.getC_Project_ID() == flnBefore.getC_Project_ID()
						&& fln.getC_SalesRegion_ID() == flnBefore.getC_SalesRegion_ID() && fln.getC_Campaign_ID() == flnBefore.getC_Campaign_ID()
						&& fln.getUser1_ID() == flnBefore.getUser1_ID() && fln.get_ValueAsString("SerNo").equalsIgnoreCase(flnBefore.get_ValueAsString("SerNo"))) { // Kalau ada FactLine yang memiliki Account yang sama
	/* #1 Kalau bukan di sisi (Debit / Credit) yang sama */
					if((flnBefore.getAmtSourceDr().compareTo(BigDecimal.ZERO) > 0 && fln.getAmtSourceCr().compareTo(BigDecimal.ZERO) > 0) 
							|| (flnBefore.getAmtSourceCr().compareTo(BigDecimal.ZERO) > 0 && fln.getAmtSourceDr().compareTo(BigDecimal.ZERO) > 0)) {
		/* Kalau Debit Before > Credit Now maka di letakkan di posisi Debit */
						if(flnBefore.getAmtSourceDr().compareTo(fln.getAmtSourceCr()) > 0) {
							BigDecimal AmtSourceDr = flnBefore.getAmtSourceDr();
							BigDecimal AmtAcctDr =flnBefore.getAmtAcctDr();
							m_lines.get(index).setAmtSourceDr(AmtSourceDr.subtract(fln.getAmtSourceCr()));
							m_lines.get(index).setAmtAcctDr(AmtAcctDr.subtract(fln.getAmtAcctCr()));
							m_lines.get(index).setAmtSourceCr(BigDecimal.ZERO);
							m_lines.get(index).setAmtAcctCr(BigDecimal.ZERO);
							flag = true;
						} 
		/* Kalau Credit Now > Debit Before maka di letakkan di posisi Credit */
						else if(fln.getAmtSourceCr().compareTo(flnBefore.getAmtSourceDr()) > 0) {
								BigDecimal AmtSourceCr = fln.getAmtSourceCr();
								BigDecimal AmtAcctCr = fln.getAmtSourceCr();
								m_lines.get(index).setAmtSourceCr(AmtSourceCr.subtract(flnBefore.getAmtSourceDr()));
								m_lines.get(index).setAmtAcctCr(AmtAcctCr.subtract(flnBefore.getAmtAcctDr()));
								m_lines.get(index).setAmtSourceDr(BigDecimal.ZERO);
								m_lines.get(index).setAmtAcctDr(BigDecimal.ZERO);
								flag = true;
						} 
		/* Kalau Debit Now > Credit Before maka di letakkan di posisi Debit */
						else if(fln.getAmtSourceDr().compareTo(flnBefore.getAmtSourceCr()) > 0) {
							BigDecimal AmtSourceDr = fln.getAmtSourceDr();
							BigDecimal AmtAcctDr = fln.getAmtAcctDr();
							m_lines.get(index).setAmtSourceDr(AmtSourceDr.subtract(flnBefore.getAmtSourceCr()));
							m_lines.get(index).setAmtAcctDr(AmtAcctDr.subtract(flnBefore.getAmtAcctCr()));
							m_lines.get(index).setAmtSourceCr(BigDecimal.ZERO);
							m_lines.get(index).setAmtAcctCr(BigDecimal.ZERO);
							flag = true;
						} 
		/* Kalau Credit Before > Debit Now maka di letakkan di posisi Credit */
						else if(flnBefore.getAmtSourceCr().compareTo(fln.getAmtSourceDr()) > 0) {
							BigDecimal AmtSourceCr = flnBefore.getAmtSourceCr();
							BigDecimal AmtAcctCr = flnBefore.getAmtAcctCr();
							m_lines.get(index).setAmtSourceCr(AmtSourceCr.subtract(fln.getAmtSourceDr()));
							m_lines.get(index).setAmtAcctCr(AmtAcctCr.subtract(fln.getAmtAcctDr()));
							m_lines.get(index).setAmtSourceDr(BigDecimal.ZERO);
							m_lines.get(index).setAmtAcctDr(BigDecimal.ZERO);
							flag = true;
						} 
	/* #2 Kalau di sisi (Debit / Credit) yang sama */
					} else {
		/* Kalau di posisi Debit*/
						if(fln.getAmtSourceDr().compareTo(BigDecimal.ZERO) > 0) {
							BigDecimal AmtSourceDr = fln.getAmtSourceDr();
							BigDecimal AmtAcctDr = fln.getAmtSourceDr();
							m_lines.get(index).setAmtSourceDr(AmtSourceDr.add(flnBefore.getAmtSourceDr()));
							m_lines.get(index).setAmtAcctDr(AmtAcctDr.add(flnBefore.getAmtAcctDr()));
							flag = true;
						}
		/* Kalau di posisi Debit*/
						else if (fln.getAmtSourceCr().compareTo(BigDecimal.ZERO) > 0) {
							BigDecimal AmtSourceCr = fln.getAmtSourceCr();
							BigDecimal AmtAcctCr = fln.getAmtSourceCr();
							m_lines.get(index).setAmtSourceCr(AmtSourceCr.add(flnBefore.getAmtSourceCr()));
							m_lines.get(index).setAmtAcctCr(AmtAcctCr.add(flnBefore.getAmtAcctCr()));
							flag = true;
						}
					}
				}else {
		/* Kalau sudah berbeda Account_ID */
					FactLineToRemove.remove(flnBefore);
					flag = true;
				}
				flnBefore = fln;
				if(flag && (index + 1) != m_lines.size())
					FactLineToRemove.add(flnBefore);
			}else {
				if(fln.getAccount_ID() == flnBefore.getAccount_ID() && fln.getC_BPartner_ID() == flnBefore.getC_BPartner_ID() 
						&& fln.getM_Product_ID() == flnBefore.getM_Product_ID() && fln.getC_Project_ID() == flnBefore.getC_Project_ID()
						&& fln.getC_SalesRegion_ID() == flnBefore.getC_SalesRegion_ID() && fln.getC_Campaign_ID() == flnBefore.getC_Campaign_ID()
						&& fln.getUser1_ID() == flnBefore.getUser1_ID()) { // Kalau ada FactLine yang memiliki Account yang sama
	/* #1 Kalau bukan di sisi (Debit / Credit) yang sama */
					if((flnBefore.getAmtSourceDr().compareTo(BigDecimal.ZERO) > 0 && fln.getAmtSourceCr().compareTo(BigDecimal.ZERO) > 0) 
							|| (flnBefore.getAmtSourceCr().compareTo(BigDecimal.ZERO) > 0 && fln.getAmtSourceDr().compareTo(BigDecimal.ZERO) > 0)) {
		/* Kalau Debit Before > Credit Now maka di letakkan di posisi Debit */
						if(flnBefore.getAmtSourceDr().compareTo(fln.getAmtSourceCr()) > 0) {
							BigDecimal AmtSourceDr = flnBefore.getAmtSourceDr();
							BigDecimal AmtAcctDr =flnBefore.getAmtAcctDr();
							m_lines.get(index).setAmtSourceDr(AmtSourceDr.subtract(fln.getAmtSourceCr()));
							m_lines.get(index).setAmtAcctDr(AmtAcctDr.subtract(fln.getAmtAcctCr()));
							m_lines.get(index).setAmtSourceCr(BigDecimal.ZERO);
							m_lines.get(index).setAmtAcctCr(BigDecimal.ZERO);
							flag = true;
						} 
		/* Kalau Credit Now > Debit Before maka di letakkan di posisi Credit */
						else if(fln.getAmtSourceCr().compareTo(flnBefore.getAmtSourceDr()) > 0) {
								BigDecimal AmtSourceCr = fln.getAmtSourceCr();
								BigDecimal AmtAcctCr = fln.getAmtSourceCr();
								m_lines.get(index).setAmtSourceCr(AmtSourceCr.subtract(flnBefore.getAmtSourceDr()));
								m_lines.get(index).setAmtAcctCr(AmtAcctCr.subtract(flnBefore.getAmtAcctDr()));
								m_lines.get(index).setAmtSourceDr(BigDecimal.ZERO);
								m_lines.get(index).setAmtAcctDr(BigDecimal.ZERO);
								flag = true;
						} 
		/* Kalau Debit Now > Credit Before maka di letakkan di posisi Debit */
						else if(fln.getAmtSourceDr().compareTo(flnBefore.getAmtSourceCr()) > 0) {
							BigDecimal AmtSourceDr = fln.getAmtSourceDr();
							BigDecimal AmtAcctDr = fln.getAmtAcctDr();
							m_lines.get(index).setAmtSourceDr(AmtSourceDr.subtract(flnBefore.getAmtSourceCr()));
							m_lines.get(index).setAmtAcctDr(AmtAcctDr.subtract(flnBefore.getAmtAcctCr()));
							m_lines.get(index).setAmtSourceCr(BigDecimal.ZERO);
							m_lines.get(index).setAmtAcctCr(BigDecimal.ZERO);
							flag = true;
						} 
		/* Kalau Credit Before > Debit Now maka di letakkan di posisi Credit */
						else if(flnBefore.getAmtSourceCr().compareTo(fln.getAmtSourceDr()) > 0) {
							BigDecimal AmtSourceCr = flnBefore.getAmtSourceCr();
							BigDecimal AmtAcctCr = flnBefore.getAmtAcctCr();
							m_lines.get(index).setAmtSourceCr(AmtSourceCr.subtract(fln.getAmtSourceDr()));
							m_lines.get(index).setAmtAcctCr(AmtAcctCr.subtract(fln.getAmtAcctDr()));
							m_lines.get(index).setAmtSourceDr(BigDecimal.ZERO);
							m_lines.get(index).setAmtAcctDr(BigDecimal.ZERO);
							flag = true;
						} 
	/* #2 Kalau di sisi (Debit / Credit) yang sama */
					} else {
		/* Kalau di posisi Debit*/
						if(fln.getAmtSourceDr().compareTo(BigDecimal.ZERO) > 0) {
							BigDecimal AmtSourceDr = fln.getAmtSourceDr();
							BigDecimal AmtAcctDr = fln.getAmtSourceDr();
							m_lines.get(index).setAmtSourceDr(AmtSourceDr.add(flnBefore.getAmtSourceDr()));
							m_lines.get(index).setAmtAcctDr(AmtAcctDr.add(flnBefore.getAmtAcctDr()));
							flag = true;
						}
		/* Kalau di posisi Debit*/
						else if (fln.getAmtSourceCr().compareTo(BigDecimal.ZERO) > 0) {
							BigDecimal AmtSourceCr = fln.getAmtSourceCr();
							BigDecimal AmtAcctCr = fln.getAmtSourceCr();
							m_lines.get(index).setAmtSourceCr(AmtSourceCr.add(flnBefore.getAmtSourceCr()));
							m_lines.get(index).setAmtAcctCr(AmtAcctCr.add(flnBefore.getAmtAcctCr()));
							flag = true;
						}
					}
				}else {
		/* Kalau sudah berbeda Account_ID */
					FactLineToRemove.remove(flnBefore);
					flag = true;
				}
				flnBefore = fln;
				if(flag && (index + 1) != m_lines.size())
					FactLineToRemove.add(flnBefore);
			}
		}

/* Looping untuk menghapus FactLine yang tidak terpakai lagi*/
		for (FactLine fln : FactLineToRemove) {
			int indexToRemove = m_lines.indexOf(fln);
			m_lines.remove(indexToRemove);
		}
	}
// END CODE JACKSON - 20200209
	
}   //  Fact