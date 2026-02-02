/**********************************************************************
* This file is part of iDempiere ERP Open Source                      *
* http://www.idempiere.org                                            *
*                                                                     *
* Copyright (C) Contributors                                          *
*                                                                     *
* This program is free software; you can redistribute it and/or       *
* modify it under the terms of the GNU General Public License         *
* as published by the Free Software Foundation; either version 2      *
* of the License, or (at your option) any later version.              *
*                                                                     *
* This program is distributed in the hope that it will be useful,     *
* but WITHOUT ANY WARRANTY; without even the implied warranty of      *
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
* GNU General Public License for more details.                        *
*                                                                     *
* You should have received a copy of the GNU General Public License   *
* along with this program; if not, write to the Free Software         *
* Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
* MA 02110-1301, USA.                                                 *
*                                                                     *
* Contributors:                                                       *
* - Adaxa                                                             *
* - Ashley Ramdass                                                    *
* - Deepak Pansheriya                                                 *
* - Murilo Ht                                                         *
* - Carlos Ruiz                                                       *
**********************************************************************/
package org.compiere.model;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.compiere.util.DB;
import org.compiere.util.Env;
import org.idempiere.cache.ImmutableIntPOCache;
import org.idempiere.cache.ImmutablePOSupport;
import org.idempiere.db.util.SQLFragment;

/**
 * Named status of records in a table via SQL criteria
 */
public class MDocumentStatus extends X_PA_DocumentStatus implements ImmutablePOSupport {
	/**
	 * generated serial id
	 */
	private static final long serialVersionUID = 4028519324986534673L;

    /**
     * UUID based Constructor
     * @param ctx  Context
     * @param PA_DocumentStatus_UU  UUID key
     * @param trxName Transaction
     */
    public MDocumentStatus(Properties ctx, String PA_DocumentStatus_UU, String trxName) {
        super(ctx, PA_DocumentStatus_UU, trxName);
    }

    /**
     * @param ctx
     * @param PA_DocumentStatus_ID
     * @param trxName
     */
	public MDocumentStatus(Properties ctx, int PA_DocumentStatus_ID, String trxName) {
		super(ctx, PA_DocumentStatus_ID, trxName);
	}
	
	/**
	 * @param ctx
	 * @param rs
	 * @param trxName
	 */
	public MDocumentStatus(Properties ctx, ResultSet rs, String trxName) {
		super(ctx, rs, trxName);
	}

	/**
	 * Copy constructor
	 * @param copy
	 */
	public MDocumentStatus(MDocumentStatus copy) 
	{
		this(Env.getCtx(), copy);
	}

	/**
	 * Copy constructor
	 * @param ctx
	 * @param copy
	 */
	public MDocumentStatus(Properties ctx, MDocumentStatus copy) 
	{
		this(ctx, copy, (String) null);
	}

	/**
	 * Copy constructor
	 * @param ctx
	 * @param copy
	 * @param trxName
	 */
	public MDocumentStatus(Properties ctx, MDocumentStatus copy, String trxName) 
	{
		this(ctx, 0, trxName);
		copyPO(copy);
	}
	
	/**	MDocumentStatus Cache				*/
	private static ImmutableIntPOCache<Integer,MDocumentStatus>	s_cache = new ImmutableIntPOCache<Integer,MDocumentStatus>(Table_Name, 20);
	
	/**
	 * 	Get from Cache (immutable)
	 *	@param ctx context
	 *	@param PA_DocumentStatus_ID id
	 *	@return document status
	 */
	public static MDocumentStatus get (Properties ctx, int PA_DocumentStatus_ID)
	{
		Integer ii = Integer.valueOf(PA_DocumentStatus_ID);
		MDocumentStatus retValue = s_cache.get(ctx, ii, e -> new MDocumentStatus(ctx, e));
		if (retValue != null)
			return retValue;
		retValue = new MDocumentStatus (ctx, PA_DocumentStatus_ID, (String)null);
		if (retValue.get_ID () == PA_DocumentStatus_ID)
		{
			s_cache.put (PA_DocumentStatus_ID, retValue, e -> new MDocumentStatus(Env.getCtx(), e));
			return retValue;
		}
		return null;
	}	//	get

	/**
	 * 	Get Document Status Indicators
	 *	@param ctx context
	 *	@param AD_User_ID user
	 * @param AD_Role_ID 
	 *	@return array of document status
	 */
	public static MDocumentStatus[] getDocumentStatusIndicators(Properties ctx, int AD_User_ID, int AD_Role_ID)
	{
		return getDocumentStatusIndicators(ctx,AD_User_ID,AD_Role_ID,null);
	}
	
	/**
	 * Get Document Status Indicators
	 * @param ctx
	 * @param AD_User_ID
	 * @param AD_Role_ID
	 * @param trxName
	 * @return array of document status
	 */
	public static MDocumentStatus[] getDocumentStatusIndicators(Properties ctx, int AD_User_ID, int AD_Role_ID, String trxName)
	{
		if (AD_User_ID < 0)
			return new MDocumentStatus[0];

		String whereClause = "AD_Client_ID IN (0,?)";

		List<MDocumentStatus> list = new Query(ctx, MDocumentStatus.Table_Name, whereClause, trxName)
				.setOnlyActiveRecords(true)
				.setOrderBy(MDocumentStatus.COLUMNNAME_SeqNo)
				.setParameters(Env.getAD_Client_ID(ctx))
				.list();

		/* Verify access for user/role */
		List<MDocumentStatus> listWithAccess = new ArrayList<MDocumentStatus>();
		for (MDocumentStatus ds : list) {
			if (ds.canAccess(ctx, AD_User_ID, AD_Role_ID, trxName)) {
				if (ds.getAD_Window_ID() > 0) {
					Boolean access = MRole.getDefault().getWindowAccess(ds.getAD_Window_ID());
					if (access != null)
						listWithAccess.add(ds);
				} else if (ds.getAD_Form_ID() > 0) {
					Boolean access = MRole.getDefault().getFormAccess(ds.getAD_Form_ID());
					if (access != null)
						listWithAccess.add(ds);
				}
			}
		}

		MDocumentStatus[] retValue = new MDocumentStatus[listWithAccess.size ()];
		listWithAccess.toArray (retValue);
		return retValue;
	}	//	getDocumentStatusIndicators

	private static String getWhereActivities(int AD_User_ID, int AD_Client_ID) {
		final String where =
				"AD_WF_Activity.Processed='N' AND AD_WF_Activity.WFState='OS' AND ("
				//	Owner of Activity
				+ " AD_WF_Activity.AD_User_ID="+AD_User_ID	//	#1
				//	Invoker (if no invoker = all)
				+ " OR EXISTS (SELECT * FROM AD_WF_Responsible r WHERE AD_WF_Activity.AD_WF_Responsible_ID=r.AD_WF_Responsible_ID"
				+ " AND r.ResponsibleType='H' AND COALESCE(r.AD_User_ID,0)=0 AND COALESCE(r.AD_Role_ID,0)=0 AND (AD_WF_Activity.AD_User_ID="+AD_User_ID+" OR AD_WF_Activity.AD_User_ID IS NULL))"	//	#2
				//  Responsible User
				+ " OR EXISTS (SELECT * FROM AD_WF_Responsible r WHERE AD_WF_Activity.AD_WF_Responsible_ID=r.AD_WF_Responsible_ID"
				+ " AND r.ResponsibleType='H' AND r.AD_User_ID="+AD_User_ID+")"		//	#3
				//	Responsible Role
				+ " OR EXISTS (SELECT * FROM AD_WF_Responsible r INNER JOIN AD_User_Roles ur ON (r.AD_Role_ID=ur.AD_Role_ID)"
				+ " WHERE AD_WF_Activity.AD_WF_Responsible_ID=r.AD_WF_Responsible_ID AND r.ResponsibleType='R' AND ur.AD_User_ID="+AD_User_ID+" AND ur.isActive = 'Y')"	//	#4
				///* Manual Responsible */ 
				+ " OR EXISTS (SELECT * FROM AD_WF_ActivityApprover r "
				+ " WHERE AD_WF_Activity.AD_WF_Activity_ID=r.AD_WF_Activity_ID AND r.AD_User_ID="+AD_User_ID+" AND r.isActive = 'Y')" 
				+ ") AND AD_WF_Activity.AD_Client_ID="+AD_Client_ID;	//	#5
		return where;
	}
	
	/**
	 * @param documentStatus
	 * @return number of matching records
	 */
	public static int evaluate(MDocumentStatus documentStatus) {
		StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ");
		String tableName = MTable.getTableName(Env.getCtx(), documentStatus.getAD_Table_ID());
		sql.append(tableName);
		
		// COMMENTED BY Andi - 20180814
//		String where = getWhereClause(documentStatus);
//		if (where != null && where.trim().length() > 0)
//			sql.append(" WHERE " ).append(where);
//		String sqlS = MRole.getDefault().addAccessSQL(sql.toString(), tableName, false, true);
// BEGIN CODE BY ANDI - 201870814
		// Reason : karena query untuk dapatkan list workflow -- pakai getWhereActivities(), berbeda dengan counting standard bawaan iDempiere di documentStatus -- pakai getWhereClause(documentStatus)
		
			String where = "";
			String sqlS = "";
			
//					System.out.println("\n\n >>> tableName : " + tableName);
			
			if(tableName.equalsIgnoreCase("AD_WF_Activity")) {
				
				where = getWhereActivities(Env.getAD_User_ID(Env.getCtx()), Env.getAD_Client_ID(Env.getCtx()));
				if (where != null && where.trim().length() > 0) {
					sql.append(" WHERE " ).append(where);
				}
				MRole role = MRole.get(Env.getCtx(), Env.getAD_Role_ID(Env.getCtx()));
				sqlS = role.addAccessSQL(sql.toString(), tableName, true, false);
//						System.out.println("\n\n >>> sqlS adwf : " + sqlS);
				
			} else {
				
				where = getWhereClause(documentStatus);
				if (where != null && where.trim().length() > 0) {
					sql.append(" WHERE " ).append(where);
				}
				sqlS = MRole.getDefault().addAccessSQL(sql.toString(), tableName, false, true);
//						System.out.println("\n\n >>> sqlS : " + sqlS);
			}
		
		// END CODE BY ANDI - 20180814
		return DB.getSQLValue(null, sqlS);
	}

	/**
	 * @param documentStatus
	 * @return where clause to find matching records
	 * @deprecated use getSQLFilter
	 */
	@Deprecated (since="13", forRemoval=true)
	public static String getWhereClause(MDocumentStatus documentStatus) {
		String tableName = MTable.getTableName(Env.getCtx(), documentStatus.getAD_Table_ID());
		StringBuilder where = new StringBuilder(" ").append(tableName).append(".AD_Client_ID=" + Env.getAD_Client_ID(Env.getCtx()) );
		if (documentStatus.getC_Project_ID() > 0) 
		{
			where.append(" AND ").append(tableName).append(".C_Project_ID=").append(documentStatus.getC_Project_ID());
		}
		if (documentStatus.getAD_Org_ID() > 0) 
		{
			where.append(" AND ").append(tableName).append(".AD_Org_ID=").append(documentStatus.getAD_Org_ID());
		}
		String extra = documentStatus.getWhereClause();
		if (extra != null && extra.trim().length() > 0)
		{
			where.append(" AND ( ").append(extra).append(" ) ");
		}
		return Env.parseContext(Env.getCtx(), 0, where.toString(), false);
	}

	/**
	 * Get where clause for document status with parameters
	 * @param documentStatus
	 * @return where clause and parameters
	 */
	public static SQLFragment getSQLFilter(MDocumentStatus documentStatus) {
		List<Object> params = new ArrayList<>();
		String tableName = MTable.getTableName(Env.getCtx(), documentStatus.getAD_Table_ID());
		StringBuilder where = new StringBuilder(" ").append(tableName).append(".AD_Client_ID=?");
		params.add(Env.getAD_Client_ID(Env.getCtx()));
		if (documentStatus.getC_Project_ID() > 0) 
		{
			where.append(" AND ").append(tableName).append(".C_Project_ID=?");
			params.add(documentStatus.getC_Project_ID());
		}
		if (documentStatus.getAD_Org_ID() > 0) 
		{
			where.append(" AND ").append(tableName).append(".AD_Org_ID=?");
			params.add(documentStatus.getAD_Org_ID());
		}
		String extra = documentStatus.getWhereClause();
		if (extra != null && extra.trim().length() > 0)
		{
			where.append(" AND ( ").append(extra).append(" ) ");
		}
		String whereClause = Env.parseContextForSql(Env.getCtx(), 0, where.toString(), false, params);
		return new SQLFragment(whereClause, params);
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("MDocumentStatus[");
		sb.append(get_ID()).append("-").append(getName())
			.append("]");
		return sb.toString();
	}

	/**
	 * Verify access against the table PA_DocumentStatusAccess
	 * @param userId  AD_User_ID
	 * @param roleId  AD_Role_ID
	 * @return true if the user/role has access
	 */
	private boolean canAccess(Properties ctx, int userId, int roleId, String trxName) {
		List<MDocumentStatusAccess> accessList = new Query(ctx, MDocumentStatusAccess.Table_Name, "PA_DocumentStatus_ID=? AND AD_Client_ID IN (0,?)", trxName)
				.setOnlyActiveRecords(true)
				.setParameters(getPA_DocumentStatus_ID(), Env.getAD_Client_ID(ctx))
				.list();

		if (accessList.size() == 0)
			return true; // no permissions set on System or Tenant - allow access

		for (MDocumentStatusAccess access : accessList) {
			/* the only problem here is that is not easy to hide things from System role or System user
			 * but as they are the administrators is not a problem
			 */
			if (   (access.getAD_Role_ID() == roleId && access.getAD_User_ID() == userId)
				|| (access.getAD_Role_ID() == roleId && access.getAD_User_ID() == 0     )   // user not set
				|| (access.getAD_Role_ID() == 0      && access.getAD_User_ID() == userId) ) // role not set
				return true;
		}

		return false;
	}

	@Override
	public PO markImmutable() {
		if (is_Immutable())
			return this;

		makeImmutable();
		return this;
	}
}
