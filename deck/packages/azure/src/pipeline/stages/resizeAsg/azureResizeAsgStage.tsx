import React from 'react';

import { PlatformHealthOverride, Registry, StageConfigField, StageConstants } from '@spinnaker/core';

import { AzureAccountRegionClusterSelector } from '../AzureAccountRegionClusterSelector';

export function AzureResizeAsgExecutionLabel({ stage }: any) {
  const context = stage.masterStage?.context || stage.context || {};

  return (
    <span className="task-label">
      {' '}
      Resize Server Group: {context.serverGroupName} ({context.region}){' '}
    </span>
  );
}

export function AzureResizeAsgStageConfig({ application, pipeline, stage, updateStageField }: any) {
  const updateStage = (changes: any) => {
    Object.assign(stage, changes);
    updateStageField(changes);
  };

  stage.regions = stage.regions || [];
  stage.cloudProvider = 'azure';
  if (
    stage.isNew &&
    application.attributes.platformHealthOnlyShowOverride &&
    stage.interestingHealthProviderNames === undefined
  ) {
    stage.interestingHealthProviderNames = [];
  }
  if (!stage.credentials && application.defaultCredentials.azure) {
    stage.credentials = application.defaultCredentials.azure;
  }
  if (!stage.regions.length && application.defaultRegions.azure) {
    stage.regions.push(application.defaultRegions.azure);
  }
  if (!stage.target) {
    stage.target = StageConstants.TARGET_LIST[0].val;
  }

  // The operation reads capacity, not targetSize; keep them in step so a resize
  // configured in the UI sends min/max/desired rather than an empty capacity.
  const updateTargetSize = (targetSize: string) => {
    const changes: any = { targetSize };
    if (targetSize !== '') {
      changes.capacity = { min: targetSize, max: targetSize, desired: targetSize };
    }
    updateStage(changes);
  };

  return (
    <div className="form-horizontal">
      {!pipeline?.strategy && (
        <AzureAccountRegionClusterSelector application={application} stage={stage} updateStageField={updateStage} />
      )}
      <StageConfigField label="Target">
        <select
          className="form-control input-sm"
          value={stage.target}
          onChange={(e) => updateStage({ target: e.target.value })}
        >
          {StageConstants.TARGET_LIST.map((target: any) => (
            <option key={target.val} value={target.val}>
              {target.label}
            </option>
          ))}
        </select>
      </StageConfigField>
      <StageConfigField label="Target Size">
        <input
          className="form-control input-sm"
          onChange={(e) => updateTargetSize(e.target.value)}
          type="number"
          value={stage.targetSize ?? ''}
        />
      </StageConfigField>
      {application.attributes.platformHealthOnlyShowOverride && (
        <PlatformHealthOverride
          interestingHealthProviderNames={stage.interestingHealthProviderNames || []}
          platformHealthType="azureService"
          onChange={(interestingHealthProviderNames: string[]) => updateStage({ interestingHealthProviderNames })}
        />
      )}
    </div>
  );
}

export function registerAzureResizeAsgStage() {
  Registry.pipeline.registerStage({
    key: 'resizeServerGroup',
    provides: 'resizeServerGroup',
    alias: 'resizeAsg',
    cloudProvider: 'azure',
    component: AzureResizeAsgStageConfig,
    executionLabelComponent: AzureResizeAsgExecutionLabel,
    validators: [
      { type: 'requiredField', fieldName: 'cluster' },
      { type: 'requiredField', fieldName: 'target' },
      { type: 'requiredField', fieldName: 'regions' },
      { type: 'requiredField', fieldName: 'credentials', fieldLabel: 'account' },
      { type: 'requiredField', fieldName: 'targetSize', fieldLabel: 'target size' },
    ],
  } as any);
}
