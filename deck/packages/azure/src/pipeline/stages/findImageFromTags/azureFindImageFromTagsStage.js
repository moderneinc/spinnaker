'use strict';

import { module } from 'angular';

import { BakeryReader, Registry } from '@spinnaker/core';

export const AZURE_PIPELINE_STAGES_FINDIMAGEFROMTAGS_AZUREFINDIMAGEFROMTAGSSTAGE =
  'spinnaker.azure.pipeline.stage.findImageFromTagsStage';
export const name = AZURE_PIPELINE_STAGES_FINDIMAGEFROMTAGS_AZUREFINDIMAGEFROMTAGSSTAGE; // for backwards compatibility
module(AZURE_PIPELINE_STAGES_FINDIMAGEFROMTAGS_AZUREFINDIMAGEFROMTAGSSTAGE, [])
  .config(function () {
    Registry.pipeline.registerStage({
      provides: 'findImageFromTags',
      cloudProvider: 'azure',
      templateUrl: require('./findImageFromTagsStage.html'),
      executionDetailsUrl: require('./findImageFromTagsExecutionDetails.html'),
      executionConfigSections: ['findImageConfig', 'taskStatus'],
      validators: [
        { type: 'requiredField', fieldName: 'packageName' },
        { type: 'requiredField', fieldName: 'tags' },
        { type: 'requiredField', fieldName: 'regions' },
      ],
    });
  })
  .controller('azureFindImageFromTagsStageCtrl', [
    '$scope',
    function ($scope) {
      $scope.stage = $scope.stage || {};
      $scope.stage.tags = $scope.stage.tags || {};
      $scope.stage.regions = $scope.stage.regions || [];
      $scope.stage.cloudProvider = $scope.stage.cloudProvider || 'azure';

      BakeryReader.getRegions('azure').then(function (regions) {
        $scope.regions = regions;
      });
    },
  ]);
