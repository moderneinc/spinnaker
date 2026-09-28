import React, { useEffect, useState } from 'react';

import type { IStageConfigProps } from '@spinnaker/core';
import {
  BakeryReader,
  ChecklistInput,
  ExecutionDetailsTasks,
  MapEditor,
  Registry,
  StageConfigField,
} from '@spinnaker/core';

export function AzureFindImageFromTagsStageConfig({ pipeline, stage, updateStageField }: IStageConfigProps) {
  const [regions, setRegions] = useState<string[]>([]);

  useEffect(() => {
    const changes: Record<string, any> = {};
    if (stage.cloudProvider !== 'azure') {
      changes.cloudProvider = 'azure';
    }
    if (!stage.tags) {
      changes.tags = {};
    }
    if (!stage.regions) {
      changes.regions = [];
    }
    if (Object.keys(changes).length) {
      updateStageField(changes);
    }
  }, []);

  useEffect(() => {
    let active = true;
    BakeryReader.getRegions('azure').then((loadedRegions) => active && setRegions(loadedRegions));
    return () => {
      active = false;
    };
  }, []);

  return (
    <div className="form-horizontal">
      <StageConfigField label="Regions">
        <ChecklistInput
          inline={true}
          name="regions"
          onChange={(event: any) => updateStageField({ regions: event.target.value })}
          showSelectAll={true}
          stringOptions={regions}
          value={stage.regions || []}
        />
      </StageConfigField>
      <StageConfigField label="Pattern">
        <input
          className="form-control input-sm"
          onChange={(event) => updateStageField({ packageName: event.target.value })}
          value={stage.packageName || ''}
        />
      </StageConfigField>
      <StageConfigField label="Tags">
        <MapEditor
          allowEmpty={true}
          model={stage.tags || {}}
          onChange={(tags) => updateStageField({ tags })}
          pipeline={pipeline}
        />
      </StageConfigField>
    </div>
  );
}

export const azureFindImageFromTagsStage = {
  key: 'findImageFromTags',
  provides: 'findImageFromTags',
  cloudProvider: 'azure',
  component: AzureFindImageFromTagsStageConfig,
  executionDetailsSections: [ExecutionDetailsTasks],
  validators: [
    { type: 'requiredField', fieldName: 'packageName' },
    { type: 'requiredField', fieldName: 'tags' },
    { type: 'requiredField', fieldName: 'regions' },
  ],
};

export function registerAzureFindImageFromTagsStage() {
  Registry.pipeline.registerStage(azureFindImageFromTagsStage);
}
