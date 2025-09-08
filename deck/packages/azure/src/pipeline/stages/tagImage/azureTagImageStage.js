'use strict';

import { module } from 'angular';

import { PipelineConfigService, Registry, StageConstants } from '@spinnaker/core';

export const AZURE_PIPELINE_STAGES_TAGIMAGE_AZURETAGIMAGESTAGE = 'spinnaker.azure.pipeline.stage.tagImageStage';
export const name = AZURE_PIPELINE_STAGES_TAGIMAGE_AZURETAGIMAGESTAGE;

module(AZURE_PIPELINE_STAGES_TAGIMAGE_AZURETAGIMAGESTAGE, [])
  .config(function () {
    Registry.pipeline.registerStage({
      provides: 'upsertImageTags',
      cloudProvider: 'azure',
      templateUrl: require('./tagImageStage.html'),
      executionDetailsUrl: require('./tagImageExecutionDetails.html'),
      executionConfigSections: ['tagImageConfig', 'taskStatus'],
    });
  })
  .controller('azureTagImageStageCtrl', [
    '$scope',
    ($scope) => {
      $scope.stage.tags = $scope.stage.tags || {};
      $scope.stage.cloudProvider = $scope.stage.cloudProvider || 'azure';

      const initUpstreamStages = () => {
        const upstreamDependencies = PipelineConfigService.getAllUpstreamDependencies(
          $scope.pipeline,
          $scope.stage,
        ).filter((stage) => StageConstants.IMAGE_PRODUCING_STAGES.includes(stage.type));
        $scope.consideredStages = new Map(upstreamDependencies.map((stage) => [stage.refId, stage.name]));
      };
      $scope.$watch('stage.requisiteStageRefIds', initUpstreamStages);
    },
  ]);
