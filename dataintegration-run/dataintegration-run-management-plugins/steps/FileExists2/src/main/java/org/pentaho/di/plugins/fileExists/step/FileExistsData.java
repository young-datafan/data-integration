/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2017 by Hitachi Vantara : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.di.plugins.fileExists.step;

import org.apache.commons.vfs2.FileObject;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.step.BaseStepData;
import org.pentaho.di.trans.step.StepDataInterface;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Samatar
 * @since 03-Juin-2008
 *
 */
public class FileExistsData extends BaseStepData implements StepDataInterface {
  public int indexOfFileename;
  public RowMetaInterface previousRowMeta;
  public RowMetaInterface outputRowMeta;
  public FileObject file;
  public int NrPrevFields;
  public List<String> tmpFiles = new ArrayList<>(2);
  public FileExistsData() {
    super();
    indexOfFileename = -1;
    file = null;
  }

}
